package org.example.miniwsa.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.example.miniwsa.event.Event;
import org.example.miniwsa.kafka.KafkaEventMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v2/events")
@Profile("async")
class AsyncIngestController {

    private final Validator validator;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    @Value("${wsa.kafka.topic.ingest}")
    private String ingestTopic;

    AsyncIngestController(Validator validator, KafkaTemplate<String, String> kafka,
                          ObjectMapper mapper) {
        this.validator = validator;
        this.kafka = kafka;
        this.mapper = mapper;
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
        events.stream()
                .sorted(Comparator.comparing(Event::timestamp).thenComparing(Event::eventId))
                .forEach(event -> publish(event, receivedAt));
        return ResponseEntity.accepted().body(new IngestAccepted(events.size(), receivedAt));
    }

    private void publish(Event event, Instant receivedAt) {
        try {
            kafka.send(ingestTopic, event.eventId(),
                    mapper.writeValueAsString(new KafkaEventMessage(receivedAt, event)));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("failed to serialize event " + event.eventId(), e);
        }
    }

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
