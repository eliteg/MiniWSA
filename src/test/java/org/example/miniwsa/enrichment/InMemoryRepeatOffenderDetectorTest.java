package org.example.miniwsa.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.example.miniwsa.event.Action;
import org.example.miniwsa.event.Category;
import org.example.miniwsa.event.Event;
import org.example.miniwsa.event.Rule;
import org.example.miniwsa.event.Severity;
import org.junit.jupiter.api.Test;

/** Repeat-offender detector (spec §6): threshold, sliding window, event-time anchoring, eviction. */
class InMemoryRepeatOffenderDetectorTest {

    private final InMemoryRepeatOffenderDetector detector = new InMemoryRepeatOffenderDetector();
    private static final Instant T0 = Instant.parse("2026-05-20T14:00:00Z");

    private static Event event(String clientIp, String eventId, Instant timestamp) {
        return new Event(eventId, timestamp, 1L, null, clientIp, null, "/x", null, null, null,
                new Rule(null, null, null, Severity.LOW, Category.BOT), Action.MONITOR, null, null, null);
    }

    @Test
    void bonusFiredOnSixthEventFromSameIp() {
        for (int i = 1; i <= 5; i++) {
            assertThat(detector.isRepeatOffender(event("ip1", "e" + i, T0.plusSeconds(i * 10L))))
                    .as("event %d should not trigger bonus", i).isFalse();
        }
        assertThat(detector.isRepeatOffender(event("ip1", "e6", T0.plusSeconds(60)))).isTrue();
    }

    @Test
    void differentIpsAreTrackedSeparately() {
        for (int i = 1; i <= 5; i++) {
            detector.isRepeatOffender(event("ip1", "e" + i, T0.plusSeconds(i * 10L)));
        }
        assertThat(detector.isRepeatOffender(event("ip2", "e-ip2", T0))).isFalse();
    }

    @Test
    void eventsOlderThanWindowDoNotCount() {
        // 5 events at minutes 1–5; a 6th event at minute 18 falls outside their window [8, 18]
        for (int i = 1; i <= 5; i++) {
            detector.isRepeatOffender(event("ip1", "e" + i, T0.plusSeconds(i * 60L)));
        }
        assertThat(detector.isRepeatOffender(event("ip1", "e6", T0.plusSeconds(18 * 60)))).isFalse();
    }

    @Test
    void eventTimeAnchoredNotWallClock() {
        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        for (int i = 1; i <= 5; i++) {
            assertThat(detector.isRepeatOffender(event("ip1", "e" + i, old.plusSeconds(i * 60L)))).isFalse();
        }
        assertThat(detector.isRepeatOffender(event("ip1", "e6", old.plusSeconds(6 * 60)))).isTrue();
    }

    @Test
    void duplicateEventIdNotCountedTwice() {
        detector.isRepeatOffender(event("ip1", "e1", T0));
        detector.isRepeatOffender(event("ip1", "e1", T0)); // duplicate — no-op
        for (int i = 2; i <= 5; i++) {
            assertThat(detector.isRepeatOffender(event("ip1", "e" + i, T0.plusSeconds(i * 10L)))).isFalse();
        }
        // 6th unique event triggers the bonus
        assertThat(detector.isRepeatOffender(event("ip1", "e6", T0.plusSeconds(60)))).isTrue();
    }

    @Test
    void outOfOrderEventWindowDoesNotIncludeFutureEvents() {
        // 5 events at T0+5min onward
        for (int i = 1; i <= 5; i++) {
            detector.isRepeatOffender(event("ip1", "e" + i, T0.plusSeconds(5 * 60 + i * 10L)));
        }
        // late event at T0+2min: window is [T0-8min, T0+2min] — the 5 future events are outside
        assertThat(detector.isRepeatOffender(event("ip1", "e-late", T0.plusSeconds(2 * 60)))).isFalse();
    }

    @Test
    void resumeBeyondWindowDoesNotTriggerBonus() {
        for (int i = 1; i <= 5; i++) {
            detector.isRepeatOffender(event("ip1", "e" + i, T0.plusSeconds(i * 60L)));
        }
        // 6th event at minute 18: first 5 (minutes 1–5) are outside window [8, 18]
        assertThat(detector.isRepeatOffender(event("ip1", "e6", T0.plusSeconds(18 * 60)))).isFalse();
    }

    @Test
    void farFutureEventDoesNotEvictEntriesNeededByLateArrivals() {
        // 5 events at minutes 1–5, then an out-of-order event at minute 12 arrives before minute 6
        for (int i = 1; i <= 5; i++) {
            detector.isRepeatOffender(event("ip1", "e" + i, T0.plusSeconds(i * 60L)));
        }
        // old code: evictBefore(12-10=2) wipes minute 1 — minute 6 later sees only 5 events → no bonus
        // new code: evictBefore(12-10-5=-3) wipes nothing — minute 6 sees all 6 events → bonus
        detector.isRepeatOffender(event("ip1", "e-future", T0.plusSeconds(12 * 60)));
        assertThat(detector.isRepeatOffender(event("ip1", "e6", T0.plusSeconds(6 * 60)))).isTrue();
    }

}
