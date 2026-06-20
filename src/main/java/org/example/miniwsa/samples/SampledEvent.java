package org.example.miniwsa.samples;

import java.time.Instant;

public record SampledEvent(
        String eventId,
        Instant timestamp,
        long configId,
        String clientIp,
        String path,
        String category,
        String severity,
        String action,
        String attackType,
        int threatScore,
        Instant receivedAt,
        String policyId,
        String hostname,
        String method,
        Integer statusCode,
        String userAgent,
        Integer requestSize,
        Integer responseSize,
        String geoCountry,
        String geoCity,
        String ruleId,
        String ruleName,
        String ruleMessage) {}
