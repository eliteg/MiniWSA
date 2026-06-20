package org.example.miniwsa.ingest;

import jakarta.validation.Validator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.example.miniwsa.enrichment.EnrichmentService;
import org.example.miniwsa.event.Event;
import org.example.miniwsa.storage.EventStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/events")
public class IngestController {

    private final Validator validator;
    private final EnrichmentService enrichmentService;
    private final EventStore eventStore;

    public IngestController(Validator validator, EnrichmentService enrichmentService,
            EventStore eventStore) {
        this.validator = validator;
        this.enrichmentService = enrichmentService;
        this.eventStore = eventStore;
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

        Instant receivedAt = Instant.now();
        eventStore.saveAll(enrichmentService.enrich(events, receivedAt));
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
