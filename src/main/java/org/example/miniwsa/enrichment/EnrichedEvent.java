package org.example.miniwsa.enrichment;

import java.time.Instant;
import org.example.miniwsa.event.Event;

/**
 * The result of enriching an event (Part 2): the original {@link Event} plus the three
 * server-assigned fields — {@code receivedAt} (ingest time), {@code attackType} (classification),
 * and {@code threatScore} (capped). This is the record that gets stored and
 * returned by the samples API. It wraps the original event rather than copying its fields.
 */
public record EnrichedEvent(Event event, Instant receivedAt, String attackType, int threatScore) {
}
