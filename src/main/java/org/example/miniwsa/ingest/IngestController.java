package org.example.miniwsa.ingest;

import jakarta.validation.Validator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.example.miniwsa.event.Event;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingestion API (Part 1, spec §8.1). Accepts a single event or an array (both bind to
 * {@code List<Event>} via {@code accept-single-value-as-array}).
 *
 * <p>Validation is <b>all-or-nothing</b>: every event is validated; if <em>any</em> fails, the
 * whole batch is rejected with {@code 400} + per-event details and nothing is accepted. This
 * fits the literal {@code 201}/{@code 400} contract (decision: no partial success).
 *
 * <p>At {@code v0.2} there is no storage yet — a valid batch is acknowledged with {@code 201};
 * persistence + enrichment arrive in {@code v0.3}.
 */
@RestController
@RequestMapping("/v1/events")
public class IngestController {

    private final Validator validator;

    public IngestController(Validator validator) {
        this.validator = validator;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Object> ingest(@RequestBody List<Event> events) {
        List<IngestError.InvalidEvent> invalid = IntStream.range(0, events.size())
                .mapToObj(i -> validate(i, events.get(i)))
                .flatMap(Optional::stream)
                .toList();

        if (!invalid.isEmpty()) {
            return ResponseEntity.badRequest().body(new IngestError("validation failed", invalid));
        }

        // All valid: assign the server-side receive time for this ingestion (spec §3 / Part 1).
        // From v0.3 this receivedAt is persisted on each stored event.
        Instant receivedAt = Instant.now();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new IngestAccepted(events.size(), receivedAt));
    }

    /** Validates one event, returning its failure (with batch index) only if it has violations. */
    private Optional<IngestError.InvalidEvent> validate(int index, Event event) {
        List<String> messages = validator.validate(event).stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .sorted()
                .toList();
        return messages.isEmpty()
                ? Optional.empty()
                : Optional.of(new IngestError.InvalidEvent(index, messages));
    }
}
