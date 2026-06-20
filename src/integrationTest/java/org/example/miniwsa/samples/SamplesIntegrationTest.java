package org.example.miniwsa.samples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SamplesIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired private TestRestTemplate template;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM events");
        for (int i = 1; i <= 5; i++) {
            insert("e" + i, 1, "1.2.3.4", "/path/" + i, 50);
        }
        insertWithCategory("e6", 2, "9.9.9.9", "/other", "XSS", 10);
        // one old event far in the past for time-range tests
        insertAt("e-old", 1, "2.2.2.2", "/old", "INJECTION", 20, "2020-01-01T00:00:00Z");
    }

    @Test
    void returnsRecentEventsWithDefaultFrom() {
        // default from = last 24h — e-old (2020) is excluded automatically
        ResponseEntity<SamplesResponse> r = template.getForEntity(
                "/v1/events/samples", SamplesResponse.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().total()).isEqualTo(6);
        assertThat(r.getBody().events()).noneMatch(e -> "e-old".equals(e.eventId()));
    }

    @Test
    void paginationLimitAndOffset() {
        SamplesResponse r = template.getForEntity(
                "/v1/events/samples?limit=2&offset=0", SamplesResponse.class).getBody();

        assertThat(r.total()).isEqualTo(6); // e-old excluded by default 24h window
        assertThat(r.events()).hasSize(2);
        assertThat(r.limit()).isEqualTo(2);
        assertThat(r.offset()).isEqualTo(0);
    }

    @Test
    void filterByConfigId() {
        SamplesResponse r = template.getForEntity(
                "/v1/events/samples?configId=1", SamplesResponse.class).getBody();

        assertThat(r.total()).isEqualTo(5); // e1-e5 only; e-old excluded by default 24h window
        assertThat(r.events()).allMatch(e -> e.configId() == 1);
    }

    @Test
    void limitCappedAt100() {
        SamplesResponse r = template.getForEntity(
                "/v1/events/samples?limit=999", SamplesResponse.class).getBody();

        assertThat(r.limit()).isEqualTo(100);
    }

    @Test
    void eventsOrderedByTimestampDesc() {
        SamplesResponse r = template.getForEntity(
                "/v1/events/samples", SamplesResponse.class).getBody();

        assertThat(r.events()).isSortedAccordingTo(
                (a, b) -> b.timestamp().compareTo(a.timestamp()));
    }

    @Test
    void offsetSkipsRecords() {
        SamplesResponse page1 = template.getForEntity(
                "/v1/events/samples?limit=2&offset=0", SamplesResponse.class).getBody();
        SamplesResponse page2 = template.getForEntity(
                "/v1/events/samples?limit=2&offset=2", SamplesResponse.class).getBody();

        assertThat(page1.events()).hasSize(2);
        assertThat(page2.events()).hasSize(2);
        // pages must not overlap
        List<String> ids1 = page1.events().stream().map(SampledEvent::eventId).toList();
        List<String> ids2 = page2.events().stream().map(SampledEvent::eventId).toList();
        assertThat(ids1).doesNotContainAnyElementsOf(ids2);
        // total is always the full count regardless of page
        assertThat(page1.total()).isEqualTo(6);
        assertThat(page2.total()).isEqualTo(6);
    }

    @Test
    void offsetBeyondTotalReturnsEmptyEvents() {
        SamplesResponse r = template.getForEntity(
                "/v1/events/samples?offset=100", SamplesResponse.class).getBody();

        assertThat(r.total()).isEqualTo(6);
        assertThat(r.events()).isEmpty();
    }

    @Test
    void filterByCategory() {
        SamplesResponse r = template.getForEntity(
                "/v1/events/samples?category=INJECTION", SamplesResponse.class).getBody();

        assertThat(r.total()).isEqualTo(5); // e1-e5 only; e-old excluded by default 24h window
        assertThat(r.events()).allMatch(e -> "INJECTION".equals(e.category()));
    }

    @Test
    void filterByAction() {
        SamplesResponse r = template.getForEntity(
                "/v1/events/samples?action=DENY", SamplesResponse.class).getBody();

        assertThat(r.total()).isEqualTo(6); // e-old excluded by default 24h window
        assertThat(r.events()).allMatch(e -> "DENY".equals(e.action()));
    }

    @Test
    void fromExcludesOlderEvents() {
        // e-old is from 2020 — filtering from 2025 should exclude it
        SamplesResponse r = template.getForEntity(
                "/v1/events/samples?from=2025-01-01T00:00:00Z",
                SamplesResponse.class).getBody();

        assertThat(r.total()).isEqualTo(6);
        assertThat(r.events()).noneMatch(e -> "e-old".equals(e.eventId()));
    }

    @Test
    void toExcludesNewerEvents() {
        // only e-old falls before 2021; explicit from overrides the 24h default
        SamplesResponse r = template.getForEntity(
                "/v1/events/samples?from=2019-01-01T00:00:00Z&to=2021-01-01T00:00:00Z",
                SamplesResponse.class).getBody();

        assertThat(r.total()).isEqualTo(1);
        assertThat(r.events().get(0).eventId()).isEqualTo("e-old");
    }

    @Test
    void configIdWithPagination() {
        SamplesResponse r = template.getForEntity(
                "/v1/events/samples?configId=1&limit=2&offset=0", SamplesResponse.class).getBody();

        assertThat(r.total()).isEqualTo(5); // e1-e5 only; e-old excluded by default 24h window
        assertThat(r.events()).hasSize(2);
        assertThat(r.events()).allMatch(e -> e.configId() == 1);
    }

    private void insert(String id, int configId, String ip, String path, int score) {
        insertWithCategory(id, configId, ip, path, "INJECTION", score);
    }

    private void insertWithCategory(String id, int configId, String ip, String path,
                                    String category, int score) {
        insertAt(id, configId, ip, path, category, score, null);
    }

    private void insertAt(String id, int configId, String ip, String path,
                          String category, int score, String isoTimestamp) {
        String ts = isoTimestamp != null ? "?::timestamptz" : "now()";
        if (isoTimestamp != null) {
            jdbc.update("INSERT INTO events (event_id, timestamp, config_id, client_ip, path, "
                    + "category, severity, action, attack_type, threat_score, received_at) "
                    + "VALUES (?, " + ts + ", ?, ?::inet, ?, ?, 'HIGH', 'DENY', 'test', ?, now())",
                    id, isoTimestamp, configId, ip, path, category, score);
        } else {
            jdbc.update("INSERT INTO events (event_id, timestamp, config_id, client_ip, path, "
                    + "category, severity, action, attack_type, threat_score, received_at) "
                    + "VALUES (?, now(), ?, ?::inet, ?, ?, 'HIGH', 'DENY', 'test', ?, now())",
                    id, configId, ip, path, category, score);
        }
    }
}
