package org.example.miniwsa.ingest;

import java.time.Instant;

/**
 * Success body for {@code POST /v1/events/ingest}: how many events were accepted and the
 * server-assigned {@code receivedAt} for the request (spec §3 / Part 1). The events themselves
 * are not echoed; from {@code v0.3} {@code receivedAt} is persisted on each stored event.
 */
public record IngestAccepted(int accepted, Instant receivedAt) {
}
