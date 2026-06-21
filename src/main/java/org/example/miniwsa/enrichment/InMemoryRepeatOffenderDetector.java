package org.example.miniwsa.enrichment;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.example.miniwsa.event.Event;
import org.springframework.stereotype.Component;

/**
 * Sliding-window implementation of {@link RepeatOffenderDetector}: returns {@code true} when more
 * than {@link #THRESHOLD} events from the same {@code clientIp} fall within {@link #WINDOW_MINUTES}
 * minutes of the current event's timestamp.
 *
 * <p><b>Grouped by minute for O(1).</b> Each IP keeps its recent event ids grouped by the minute
 * they occurred; counting sums the ~11 minutes covering the window, and eviction is minute-index
 * arithmetic — never a scan over events.
 *
 * <p><b>Event-time, never wall-clock.</b> Both the count window and eviction are anchored on the
 * event's own {@code timestamp}, so replayed/backdated data counts correctly.
 *
 * <p><b>Out-of-order safety.</b> Eviction is based on the furthest timestamp seen for each IP
 * (not the current event's timestamp), minus the window, minus a lateness tolerance. This ensures
 * a far-future event does not prematurely evict entries that a still-pending late event would need.
 *
 * <p><b>Memory.</b> IPs that stop sending events are never removed from the map — the map grows
 * unboundedly with distinct IPs seen. Production fix: replace with a Redis-backed implementation
 * where each IP's data expires automatically via TTL.
 */
@Component
public class InMemoryRepeatOffenderDetector implements RepeatOffenderDetector {

    static final int WINDOW_MINUTES = 10;
    public static final int THRESHOLD = 5;
    static final int LATENESS_TOLERANCE_MINUTES = 5;

    private final ConcurrentHashMap<String, IpMinutes> byClientIp = new ConcurrentHashMap<>();

    @Override
    public boolean isRepeatOffender(Event event) {
        long minute = minuteOf(event.timestamp());
        boolean[] result = {false};
        byClientIp.compute(event.clientIp(), (ip, existing) -> {
            IpMinutes minutes = (existing == null) ? new IpMinutes() : existing;
            minutes.add(minute, event.eventId());
            long evictBefore = minutes.maxMinute() - WINDOW_MINUTES - LATENESS_TOLERANCE_MINUTES;
            minutes.evictBefore(evictBefore);
            result[0] = minutes.countBetween(minute - WINDOW_MINUTES, minute) > THRESHOLD;
            return minutes;
        });
        return result[0];
    }

    private static long minuteOf(Instant timestamp) {
        return timestamp.getEpochSecond() / 60;
    }

    private static final class IpMinutes {

        private final Map<Long, Set<String>> eventIdsByMinute = new HashMap<>();
        private long maxMinute = Long.MIN_VALUE;

        void add(long minute, String eventId) {
            eventIdsByMinute.computeIfAbsent(minute, m -> new HashSet<>()).add(eventId);
            if (minute > maxMinute) maxMinute = minute;
        }

        long maxMinute() {
            return maxMinute;
        }

        int countBetween(long fromMinute, long toMinute) {
            int sum = 0;
            for (long m = fromMinute; m <= toMinute; m++) {
                Set<String> ids = eventIdsByMinute.get(m);
                if (ids != null) sum += ids.size();
            }
            return sum;
        }

        void evictBefore(long oldestMinuteToKeep) {
            eventIdsByMinute.keySet().removeIf(m -> m < oldestMinuteToKeep);
        }
    }
}
