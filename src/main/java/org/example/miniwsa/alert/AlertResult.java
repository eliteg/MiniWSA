package org.example.miniwsa.alert;

public record AlertResult(
        long ruleId,
        String category,
        int threshold,
        int windowMinutes,
        long currentCount,
        boolean firing) {}
