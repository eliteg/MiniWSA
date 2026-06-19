package org.example.miniwsa.event;

import jakarta.validation.constraints.NotNull;

/**
 * The WAF rule that matched. {@code severity} and {@code category} are <b>required</b> — they
 * feed the threat score and classification (spec §3). {@code id}/{@code name}/{@code message}
 * are informational (optional).
 */
public record Rule(
        String id,
        String name,
        String message,
        @NotNull Severity severity,
        @NotNull Category category) {
}
