package org.example.miniwsa.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.example.miniwsa.enrichment.EnrichedEvent;
import org.example.miniwsa.event.Action;
import org.example.miniwsa.event.Category;
import org.example.miniwsa.event.Event;
import org.example.miniwsa.event.GeoLocation;
import org.example.miniwsa.event.Rule;
import org.example.miniwsa.event.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class EventStoreIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private EventStore eventStore;

    @Autowired
    private JdbcTemplate jdbc;

    private static final Instant T = Instant.parse("2026-05-20T14:32:10Z");
    private static final Instant RECEIVED = Instant.parse("2026-05-20T14:32:11Z");

    @Test
    void savesEnrichedEventsToDatabase() {
        Event e1 = event("evt-store-1", "1.2.3.4");
        Event e2 = event("evt-store-2", "5.6.7.8");

        eventStore.saveAll(List.of(
                new EnrichedEvent(e1, RECEIVED, "SQL/Command Injection", 70),
                new EnrichedEvent(e2, RECEIVED, "SQL/Command Injection", 55)));

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE event_id IN ('evt-store-1','evt-store-2')",
                Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void promotedColumnsAreStoredCorrectly() {
        Event e = event("evt-store-cols", "9.9.9.9");
        eventStore.saveAll(List.of(new EnrichedEvent(e, RECEIVED, "SQL/Command Injection", 65)));

        String attackType = jdbc.queryForObject(
                "SELECT attack_type FROM events WHERE event_id = 'evt-store-cols'", String.class);
        Integer score = jdbc.queryForObject(
                "SELECT threat_score FROM events WHERE event_id = 'evt-store-cols'", Integer.class);

        assertThat(attackType).isEqualTo("SQL/Command Injection");
        assertThat(score).isEqualTo(65);
    }

    @Test
    void duplicateEventIdIsIgnored() {
        Event e = event("evt-store-dup", "1.1.1.1");
        EnrichedEvent enriched = new EnrichedEvent(e, RECEIVED, "SQL/Command Injection", 40);

        eventStore.saveAll(List.of(enriched));
        eventStore.saveAll(List.of(enriched));

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE event_id = 'evt-store-dup'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void geoColumnsAreStoredWhenPresent() {
        Event e = new Event("evt-store-geo", T, 14227L, null, "2.2.2.2", null, "/api/login",
                null, null, null,
                new Rule(null, null, null, Severity.HIGH, Category.BOT), Action.MONITOR,
                new GeoLocation("IL", "Tel Aviv"), null, null);

        eventStore.saveAll(List.of(new EnrichedEvent(e, RECEIVED, "Bot Activity", 30)));

        String country = jdbc.queryForObject(
                "SELECT geo_country FROM events WHERE event_id = 'evt-store-geo'", String.class);
        String city = jdbc.queryForObject(
                "SELECT geo_city FROM events WHERE event_id = 'evt-store-geo'", String.class);

        assertThat(country).isEqualTo("IL");
        assertThat(city).isEqualTo("Tel Aviv");
    }

    private Event event(String id, String ip) {
        return new Event(id, T, 14227L, null, ip, null, "/api/login", null, null, null,
                new Rule(null, null, null, Severity.CRITICAL, Category.INJECTION), Action.DENY, null, null, null);
    }
}
