package org.example.miniwsa.enrichment;

import org.example.miniwsa.event.Event;

public interface RepeatOffenderDetector {
    boolean isRepeatOffender(Event event);
}
