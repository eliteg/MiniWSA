package org.example.miniwsa.enrichment;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.example.miniwsa.event.Event;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sliding-window counter for the repeat-offender bonus: "more than
 * {@link #THRESHOLD} events from the same {@code clientIp} within {@link #WINDOW_MINUTES} minutes".
 * The only stateful, hot-path piece of enrichment.
 *
 * <p><b>Grouped by minute for O(1).</b> Each IP keeps its recent event ids grouped by the minute
 * they occurred; counting sums the ~11 minutes covering the window, and eviction is minute-index
 * arithmetic — never a scan over events (which would be O(n) under a same-IP flood).
 *
 * <p><b>Event-time, never wall-clock.</b> Both the count window and per-event eviction are anchored
 * on the event's own {@code timestamp}, so replayed/backdated data counts correctly and one IP's
 * activity can't evict another's data.
 *
 * <p><b>Thread-safety + memory.</b> All per-IP work runs inside {@link ConcurrentHashMap#compute},
 * which holds the per-key lock — so concurrent updates to the same IP serialize and removal is
 * atomic. A periodic sweep ({@link #evictIdleIps()}) drops IPs whose window has aged out; without
 * it the map would grow with every distinct IP ever seen (a spoofed-IP flood → OOM).
 */
@Component
public class RepeatOffenderWindow {

    static final int WINDOW_MINUTES = 10;
    public static final int THRESHOLD = 5;

    private final ConcurrentHashMap<String, IpMinutes> byClientIp = new ConcurrentHashMap<>();
    private final AtomicLong latestMinuteSeen = new AtomicLong(Long.MIN_VALUE);

    /**
     * Records the event in its IP's window and returns the in-window event count for that IP,
     * including this event. The caller flags a repeat offender when the count exceeds
     * {@link #THRESHOLD}.
     */
    public int recordAndCount(Event event) {
        long minute = minuteOf(event.timestamp());
        latestMinuteSeen.updateAndGet(prev -> Math.max(prev, minute));
        int[] count = {0};
        byClientIp.compute(event.clientIp(), (ip, existing) -> {
            IpMinutes minutes = (existing == null) ? new IpMinutes() : existing;
            minutes.evictBefore(minute - WINDOW_MINUTES);                       // event-time eviction
            minutes.add(minute, event.eventId());
            count[0] = minutes.countBetween(minute - WINDOW_MINUTES, minute);   // bounded to [min-10, min]
            return minutes;                                                     // never empty here
        });
        return count[0];
    }

    /**
     * Drops IPs whose window has fully aged out, freeing memory. Removal happens inside
     * {@code compute()} so it can't race a concurrent {@link #recordAndCount} on the same IP
     * (the lost-update hazard of a plain {@code removeIf}). Runs every 5 minutes.
     */
    @Scheduled(fixedRate = 300_000)
    void evictIdleIps() {
        long cutoff = latestMinuteSeen.get() - WINDOW_MINUTES;
        for (String ip : byClientIp.keySet()) {
            byClientIp.compute(ip, (k, minutes) -> {
                minutes.evictBefore(cutoff);
                return minutes.isEmpty() ? null : minutes;   // null → atomic remove of the key
            });
        }
    }

    /** Number of IPs currently tracked — for tests asserting idle IPs are reclaimed. */
    int trackedIpCount() {
        return byClientIp.size();
    }

    private static long minuteOf(Instant timestamp) {
        return timestamp.getEpochSecond() / 60;
    }

    /** One client's recent event ids grouped by minute. Only ever touched inside {@code compute()}. */
    private static final class IpMinutes {

        private final Map<Long, Set<String>> eventIdsByMinute = new HashMap<>();

        void add(long minute, String eventId) {
            eventIdsByMinute.computeIfAbsent(minute, m -> new HashSet<>()).add(eventId);
        }

        int countBetween(long fromMinute, long toMinute) {
            int sum = 0;
            for (long minute = fromMinute; minute <= toMinute; minute++) {
                Set<String> ids = eventIdsByMinute.get(minute);
                if (ids != null) {
                    sum += ids.size();
                }
            }
            return sum;
        }

        void evictBefore(long oldestMinuteToKeep) {
            eventIdsByMinute.keySet().removeIf(minute -> minute < oldestMinuteToKeep);
        }

        boolean isEmpty() {
            return eventIdsByMinute.isEmpty();
        }
    }
}
