package org.example.miniwsa.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.example.miniwsa.event.Action;
import org.example.miniwsa.event.Category;
import org.example.miniwsa.event.Event;
import org.example.miniwsa.event.Rule;
import org.example.miniwsa.event.Severity;
import org.junit.jupiter.api.Test;

/**
 * Enrichment orchestration (Part 2 / spec §6): classify + score per event, and the intra-batch
 * ordering that makes the repeat-offender count deterministic.
 */
class EnrichmentServiceTest {

    private final EnrichmentService service = new EnrichmentService(
            new AttackTypeClassifier(), new ThreatScorer(100), new RepeatOffenderWindow());

    private static final Instant T0 = Instant.parse("2026-05-20T14:00:00Z");
    private static final Instant RECEIVED = Instant.parse("2026-06-19T20:00:00Z");

    private static Event event(String eventId, String clientIp, Instant timestamp,
                               Severity severity, Action action, String path, Category category) {
        return new Event(eventId, timestamp, 1L, null, clientIp, null, path, null, null, null,
                new Rule(null, null, null, severity, category), action, null, null, null);
    }

    private List<Event> sameIpWave(String clientIp, int n) {
        return IntStream.rangeClosed(1, n)
                .mapToObj(i -> event("e" + i, clientIp, T0.plusSeconds(i * 10L),
                        Severity.LOW, Action.MONITOR, "/x", Category.BOT))
                .toList();
    }

    @Test
    void enrichesAttackTypeReceivedAtAndScore() {
        Event e = event("e1", "1.1.1.1", T0, Severity.CRITICAL, Action.DENY, "/admin", Category.INJECTION);

        EnrichedEvent enriched = service.enrich(List.of(e), RECEIVED).get(0);

        assertThat(enriched.attackType()).isEqualTo("SQL/Command Injection");
        assertThat(enriched.receivedAt()).isEqualTo(RECEIVED);
        assertThat(enriched.event()).isEqualTo(e);
        // CRITICAL(40) + DENY(20) + /admin(15), not a repeat offender (1st event) = 75
        assertThat(enriched.threatScore()).isEqualTo(75);
    }

    @Test
    void sameIpWaveFiresRepeatOffenderOnTheSixthEvent() {
        List<EnrichedEvent> enriched = service.enrich(sameIpWave("9.9.9.9", 6), RECEIVED);

        // base score LOW(10) + MONITOR(0) + non-sensitive path(0); +15 only once count > 5
        for (int i = 0; i < 5; i++) {
            assertThat(enriched.get(i).threatScore()).as("event %d", i + 1).isEqualTo(10);
        }
        assertThat(enriched.get(5).threatScore()).isEqualTo(25); // 6th: 10 + repeat-offender 15
    }

    @Test
    void arrivalOrderDoesNotMatterBecauseTheBatchIsSorted() {
        // same wave, but reversed on input — sorting by timestamp restores event-time order,
        // so the latest-timestamp event is still the one that trips the +15
        List<Event> reversed = sameIpWave("8.8.8.8", 6).reversed();

        List<EnrichedEvent> enriched = service.enrich(reversed, RECEIVED);

        assertThat(enriched.get(0).event().eventId()).isEqualTo("e1"); // output is sorted ascending
        assertThat(enriched.get(5).event().eventId()).isEqualTo("e6");
        assertThat(enriched.get(5).threatScore()).isEqualTo(25);       // 6th in time → +15
        assertThat(enriched.get(0).threatScore()).isEqualTo(10);
    }
}
