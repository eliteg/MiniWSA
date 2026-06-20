package org.example.miniwsa.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.example.miniwsa.event.Action;
import org.example.miniwsa.event.Category;
import org.example.miniwsa.event.Event;
import org.example.miniwsa.event.Rule;
import org.example.miniwsa.event.Severity;
import org.junit.jupiter.api.Test;

/** Repeat-offender window (spec §6): per-IP counting, the threshold, eviction, event-time anchoring. */
class RepeatOffenderWindowTest {

    private final RepeatOffenderWindow window = new RepeatOffenderWindow();
    private static final Instant T0 = Instant.parse("2026-05-20T14:00:00Z");

    /** Minimal event — the window only reads clientIp, eventId, and timestamp. */
    private static Event event(String clientIp, String eventId, Instant timestamp) {
        return new Event(eventId, timestamp, 1L, null, clientIp, null, "/x", null, null, null,
                new Rule(null, null, null, Severity.LOW, Category.BOT), Action.MONITOR, null, null, null);
    }

    @Test
    void countsEventsFromTheSameIp() {
        assertThat(window.recordAndCount(event("ip1", "e1", T0))).isEqualTo(1);
        assertThat(window.recordAndCount(event("ip1", "e2", T0.plusSeconds(60)))).isEqualTo(2);
        assertThat(window.recordAndCount(event("ip1", "e3", T0.plusSeconds(120)))).isEqualTo(3);
    }

    @Test
    void differentIpsAreCountedSeparately() {
        window.recordAndCount(event("ip1", "e1", T0));
        window.recordAndCount(event("ip1", "e2", T0));
        assertThat(window.recordAndCount(event("ip2", "e3", T0))).isEqualTo(1);
    }

    @Test
    void sixthEventFromSameIpExceedsThreshold() {
        int count = 0;
        for (int i = 1; i <= 6; i++) {
            count = window.recordAndCount(event("ip1", "e" + i, T0.plusSeconds(i * 10L)));
        }
        assertThat(count).isEqualTo(6);
        assertThat(count).isGreaterThan(RepeatOffenderWindow.THRESHOLD); // 6 > 5 → repeat offender
    }

    @Test
    void eventsWithinTheWindowAreCounted() {
        window.recordAndCount(event("ip1", "e1", T0));
        // 9 minutes later — still inside the 10-minute window
        assertThat(window.recordAndCount(event("ip1", "e2", T0.plusSeconds(9 * 60)))).isEqualTo(2);
    }

    @Test
    void eventsOlderThanTheWindowAreEvicted() {
        window.recordAndCount(event("ip1", "e1", T0));
        // 11 minutes later — the first event has aged out
        assertThat(window.recordAndCount(event("ip1", "e2", T0.plusSeconds(11 * 60)))).isEqualTo(1);
    }

    @Test
    void countIsAnchoredOnEventTimeNotWallClock() {
        // Backdated/replayed data: timestamps are years in the past, but counting still works
        // because the window anchors on the events' own timestamps, not now().
        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        assertThat(window.recordAndCount(event("ip1", "e1", old))).isEqualTo(1);
        assertThat(window.recordAndCount(event("ip1", "e2", old.plusSeconds(60)))).isEqualTo(2);
    }

    @Test
    void reAddingTheSameEventIdDoesNotInflateTheCount() {
        window.recordAndCount(event("ip1", "e1", T0));
        // same eventId again (a duplicate) — Set semantics: a no-op
        assertThat(window.recordAndCount(event("ip1", "e1", T0))).isEqualTo(1);
    }

    @Test
    void outOfOrderEventDoesNotCountFutureEvents() {
        // events arrive out of order: 14:00, then 14:05, then a late 14:02
        window.recordAndCount(event("ip1", "e1", T0));                       // minute 0
        window.recordAndCount(event("ip1", "e2", T0.plusSeconds(5 * 60)));   // minute 5
        // the late event at minute 2: its window is [-8, 2], so the minute-5 event is in the
        // FUTURE and must NOT be counted → count is {e1, e3} = 2 (not 3)
        assertThat(window.recordAndCount(event("ip1", "e3", T0.plusSeconds(2 * 60)))).isEqualTo(2);
    }

    @Test
    void lullThenResumeStillCountsTheWholeWindow() {
        // your scenario: 4 events at minutes 1–4 ...
        window.recordAndCount(event("ip1", "e1", T0.plusSeconds(1 * 60)));
        window.recordAndCount(event("ip1", "e2", T0.plusSeconds(2 * 60)));
        window.recordAndCount(event("ip1", "e3", T0.plusSeconds(3 * 60)));
        window.recordAndCount(event("ip1", "e4", T0.plusSeconds(4 * 60)));
        // ... then a quiet spell during which the janitor runs (no new events) ...
        window.evictIdleIps();
        window.evictIdleIps();
        window.evictIdleIps();
        // ... then a 5th event at minute 5: all 5 are still inside the 10-minute window
        assertThat(window.recordAndCount(event("ip1", "e5", T0.plusSeconds(5 * 60)))).isEqualTo(5);
    }

    @Test
    void resumeBeyondTheWindowCountsOnlyTheNewEvent() {
        window.recordAndCount(event("ip1", "e1", T0.plusSeconds(1 * 60)));
        window.recordAndCount(event("ip1", "e2", T0.plusSeconds(2 * 60)));
        window.recordAndCount(event("ip1", "e3", T0.plusSeconds(3 * 60)));
        window.recordAndCount(event("ip1", "e4", T0.plusSeconds(4 * 60)));
        // event at minute 18: minutes 1–4 are >10 min older → outside window [8,18] → not counted
        assertThat(window.recordAndCount(event("ip1", "e5", T0.plusSeconds(18 * 60)))).isEqualTo(1);
    }

    @Test
    void idleIpsAreReclaimed() {
        window.recordAndCount(event("ip-old", "e1", T0));
        // a much later event for another IP advances the global clock past ip-old's window
        window.recordAndCount(event("ip-live", "e2", T0.plusSeconds(20 * 60)));
        assertThat(window.trackedIpCount()).isEqualTo(2);

        window.evictIdleIps();

        // ip-old has aged out and is removed; ip-live stays
        assertThat(window.trackedIpCount()).isEqualTo(1);
    }
}
