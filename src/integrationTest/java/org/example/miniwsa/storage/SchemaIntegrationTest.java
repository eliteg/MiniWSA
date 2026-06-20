package org.example.miniwsa.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the Liquibase schema against a real Postgres (Testcontainers): the migrations apply,
 * the tables exist, and the {@code threat_score} CHECK rejects out-of-range values (spec §7).
 */
@SpringBootTest
@Testcontainers
class SchemaIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void liquibaseCreatesTheEventsAndAlertRulesTables() {
        // tables exist and are empty → Liquibase ran on startup
        assertThat(jdbc.queryForObject("SELECT count(*) FROM events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_rules", Integer.class)).isZero();
    }

    @Test
    void eventsIndexesExist() {
        Integer indexes = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'events'", Integer.class);
        // 3 explicit indexes + the primary key index
        assertThat(indexes).isGreaterThanOrEqualTo(4);
    }

    @Test
    void threatScoreCheckRejectsOutOfRange() {
        assertThatThrownBy(() -> insertEvent(150))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void threatScoreCheckAcceptsInRange() {
        insertEvent(90);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM events", Integer.class)).isEqualTo(1);
    }

    private void insertEvent(int threatScore) {
        jdbc.update("""
                INSERT INTO events (event_id, timestamp, config_id, client_ip, path, category,
                                    severity, action, attack_type, threat_score, received_at)
                VALUES (?, now(), 1, '1.2.3.4', '/x', 'BOT', 'LOW', 'MONITOR', 'Bot Activity', ?, now())
                """, "evt-" + threatScore, threatScore);
    }
}
