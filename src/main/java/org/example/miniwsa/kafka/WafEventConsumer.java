package org.example.miniwsa.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.example.miniwsa.enrichment.EnrichmentService;
import org.example.miniwsa.storage.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Profile("async")
class WafEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(WafEventConsumer.class);

    private final EnrichmentService enrichmentService;
    private final EventStore eventStore;
    private final ObjectMapper mapper;

    WafEventConsumer(EnrichmentService enrichmentService, EventStore eventStore, ObjectMapper mapper) {
        this.enrichmentService = enrichmentService;
        this.eventStore = eventStore;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${wsa.kafka.topic.ingest}")
    public void consume(String message) throws Exception {
        KafkaEventMessage msg = mapper.readValue(message, KafkaEventMessage.class);
        eventStore.saveAll(enrichmentService.enrich(List.of(msg.event()), msg.receivedAt()));
        log.debug("stored event {}", msg.event().eventId());
    }
}
