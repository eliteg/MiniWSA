package org.example.miniwsa.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * A security event (DLR) as received on ingestion. The required fields are exactly those the
 * system can't function without — they feed enrichment, keying, or the mandated stats (spec §3).
 * Everything else is optional and informational.
 *
 * <p>Validation is two-layered: <b>Jackson</b> rejects bad enum values and bad timestamp formats
 * at deserialization; <b>Bean Validation</b> ({@code @NotBlank}/{@code @NotNull}) rejects missing
 * required fields. Server-assigned fields ({@code receivedAt}, {@code attackType},
 * {@code threatScore}) are <b>not</b> part of this DTO — {@code ignoreUnknown} drops them if a
 * client sends them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Event(
        @NotBlank String eventId,
        @NotNull Instant timestamp,
        @NotNull Long configId,
        String policyId,
        @NotBlank String clientIp,
        String hostname,
        @NotBlank String path,
        String method,
        Integer statusCode,
        String userAgent,
        @NotNull @Valid Rule rule,
        @NotNull Action action,
        GeoLocation geoLocation,
        Integer requestSize,
        Integer responseSize) {
}
