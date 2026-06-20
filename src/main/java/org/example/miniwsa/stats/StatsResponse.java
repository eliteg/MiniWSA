package org.example.miniwsa.stats;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record StatsResponse(
        long totalEvents,
        Map<String, CategoryStat> byCategory,
        Map<String, Long> byAction,
        List<AttackerStat> topAttackers,
        List<PathStat> topTargetedPaths) {

    public record CategoryStat(long count, BigDecimal avgThreatScore) {}
    public record AttackerStat(String clientIp, long count, BigDecimal avgThreatScore) {}
    public record PathStat(String path, long count) {}
}
