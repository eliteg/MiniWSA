package org.example.miniwsa.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AlertIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AlertIntegrationTest.class);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired private TestRestTemplate template;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM alert_rules");
    }

    @Test
    void firingAlertAppearsWhenCountExceedsThreshold() throws Exception {
        // define a rule: fire if > 2 INJECTION events in the last 10 minutes
        ResponseEntity<AlertRule> define = template.postForEntity(
                "/v1/alerts/define",
                Map.of("category", "INJECTION", "threshold", 2, "windowMinutes", 10),
                AlertRule.class);

        log.info("\n=== Define response (expect 201) ===\n{}\n===================================",
                define.getBody());
        assertThat(define.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(define.getBody().id()).isPositive();

        // seed 3 INJECTION events within the window → should fire
        ingestEvents("INJECTION", 3);

        ResponseEntity<List<AlertResult>> evaluate = template.exchange(
                "/v1/alerts/evaluate", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        log.info("\n=== Evaluate response (expect firing=true) ===");
        evaluate.getBody().forEach(r -> log.info("  ruleId={} category={} threshold={} currentCount={} firing={}",
                r.ruleId(), r.category(), r.threshold(), r.currentCount(), r.firing()));

        assertThat(evaluate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(evaluate.getBody()).hasSize(1);
        AlertResult result = evaluate.getBody().get(0);
        assertThat(result.category()).isEqualTo("INJECTION");
        assertThat(result.currentCount()).isEqualTo(3);
        assertThat(result.firing()).isTrue();
    }

    @Test
    void alertNotFiringWhenBelowThreshold() {
        template.postForEntity(
                "/v1/alerts/define",
                Map.of("category", "INJECTION", "threshold", 10, "windowMinutes", 10),
                AlertRule.class);

        // seed only 2 events — below threshold of 10
        ingestEvents("INJECTION", 2);

        ResponseEntity<List<AlertResult>> evaluate = template.exchange(
                "/v1/alerts/evaluate", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        log.info("\n=== Evaluate response (expect firing=false) ===");
        evaluate.getBody().forEach(r -> log.info("  ruleId={} category={} threshold={} currentCount={} firing={}",
                r.ruleId(), r.category(), r.threshold(), r.currentCount(), r.firing()));

        AlertResult result = evaluate.getBody().get(0);
        assertThat(result.currentCount()).isEqualTo(2);
        assertThat(result.firing()).isFalse();
    }

    private void ingestEvents(String category, int count) {
        List<Map<String, Object>> events = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(Map.of(
                    "eventId",   "alert-test-" + UUID.randomUUID(),
                    "timestamp", java.time.Instant.now().minusSeconds(i * 30L).toString(),
                    "configId",  1,
                    "clientIp",  "1.2.3.4",
                    "path",      "/api/v1/data",
                    "rule",      Map.of("severity", "HIGH", "category", category),
                    "action",    "DENY"));
        }
        template.postForEntity("/v1/events/ingest", events, String.class);
    }
}
