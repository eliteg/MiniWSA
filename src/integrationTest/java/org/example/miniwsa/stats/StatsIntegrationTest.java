package org.example.miniwsa.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
class StatsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private TestRestTemplate template;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM events");
        // configId 1: 2 INJECTION/DENY/CRITICAL, 1 XSS/ALERT/HIGH
        insert("e1", 1, "1.2.3.4", "/admin",  "INJECTION", "CRITICAL", "DENY",    70);
        insert("e2", 1, "1.2.3.4", "/admin",  "INJECTION", "CRITICAL", "DENY",    80);
        insert("e3", 1, "5.6.7.8", "/login",  "XSS",       "HIGH",     "ALERT",   50);
        // configId 2: 1 event — must be excluded when filtering by configId=1
        insert("e4", 2, "9.9.9.9", "/other",  "BOT",       "LOW",      "MONITOR", 10);
    }

    @Test
    void statsForConfigIdReturnsCorrectTotals() {
        ResponseEntity<StatsResponse> r = template.getForEntity(
                "/v1/events/stats?configId=1", StatsResponse.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        StatsResponse s = r.getBody();
        assertThat(s.totalEvents()).isEqualTo(3);
    }

    @Test
    void byCategoryAggregatesCorrectly() {
        StatsResponse s = template.getForEntity(
                "/v1/events/stats?configId=1", StatsResponse.class).getBody();

        assertThat(s.byCategory()).containsKey("INJECTION");
        StatsResponse.CategoryStat inj = s.byCategory().get("INJECTION");
        assertThat(inj.count()).isEqualTo(2);
        assertThat(inj.avgThreatScore()).isEqualTo(new BigDecimal("75.0"));
    }

    @Test
    void byActionAggregatesCorrectly() {
        StatsResponse s = template.getForEntity(
                "/v1/events/stats?configId=1", StatsResponse.class).getBody();

        assertThat(s.byAction()).containsEntry("DENY", 2L).containsEntry("ALERT", 1L);
    }

    @Test
    void topAttackersRankedByCount() {
        StatsResponse s = template.getForEntity(
                "/v1/events/stats?configId=1", StatsResponse.class).getBody();

        assertThat(s.topAttackers()).isNotEmpty();
        assertThat(s.topAttackers().get(0).clientIp()).isEqualTo("1.2.3.4");
        assertThat(s.topAttackers().get(0).count()).isEqualTo(2);
    }

    @Test
    void topTargetedPathsRankedByCount() {
        StatsResponse s = template.getForEntity(
                "/v1/events/stats?configId=1", StatsResponse.class).getBody();

        assertThat(s.topTargetedPaths()).isNotEmpty();
        assertThat(s.topTargetedPaths().get(0).path()).isEqualTo("/admin");
        assertThat(s.topTargetedPaths().get(0).count()).isEqualTo(2);
    }

    @Test
    void omittingConfigIdAggregatesAcrossAll() {
        StatsResponse s = template.getForEntity(
                "/v1/events/stats", StatsResponse.class).getBody();

        assertThat(s.totalEvents()).isEqualTo(4);
    }

    private void insert(String id, int configId, String ip, String path,
                        String category, String severity, String action, int score) {
        jdbc.update("""
                INSERT INTO events (event_id, timestamp, config_id, client_ip, path,
                                    category, severity, action, attack_type, threat_score, received_at)
                VALUES (?, now(), ?, ?::inet, ?, ?, ?, ?, 'test', ?, now())
                """, id, configId, ip, path, category, severity, action, score);
    }
}
