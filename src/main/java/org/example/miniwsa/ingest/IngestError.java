package org.example.miniwsa.ingest;

import java.util.List;

/**
 * Uniform {@code 400} body for ingestion. {@code error} is a short summary; {@code invalidEvents}
 * lists which event (by index in the batch) failed and the reasons. Both per-field validation
 * failures and Jackson parse failures (bad enum/timestamp) map to this same shape; a body that
 * can't be parsed at all has an empty {@code invalidEvents}.
 */
public record IngestError(String error, List<InvalidEvent> invalidEvents) {

    /** One rejected event: its position in the batch and the messages against it. */
    public record InvalidEvent(int index, List<String> errors) {
    }
}
