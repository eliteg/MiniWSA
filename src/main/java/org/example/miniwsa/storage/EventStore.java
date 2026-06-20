package org.example.miniwsa.storage;

import java.util.List;
import org.example.miniwsa.enrichment.EnrichedEvent;

public interface EventStore {
    void saveAll(List<EnrichedEvent> events);
}
