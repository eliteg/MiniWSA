package org.example.miniwsa.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IngestPersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private TestRestTemplate template;

    @Autowired
    private JdbcTemplate jdbc;

    private static final String EVENT = """
            {
              "eventId": "evt-e2e-1",
              "timestamp": "2026-05-20T14:32:10Z",
              "configId": 14227,
              "clientIp": "203.0.113.42",
              "path": "/api/v1/login",
              "rule": { "severity": "CRITICAL", "category": "INJECTION" },
              "action": "DENY"
            }
            """;

    @Test
    void postedEventIsPersistedWithEnrichment() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = template.postForEntity(
                "/v1/events/ingest", new HttpEntity<>(EVENT, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE event_id = 'evt-e2e-1'", Integer.class);
        assertThat(count).isEqualTo(1);

        String attackType = jdbc.queryForObject(
                "SELECT attack_type FROM events WHERE event_id = 'evt-e2e-1'", String.class);
        assertThat(attackType).isEqualTo("SQL/Command Injection");

        Integer score = jdbc.queryForObject(
                "SELECT threat_score FROM events WHERE event_id = 'evt-e2e-1'", Integer.class);
        assertThat(score).isGreaterThan(0);
    }
}
