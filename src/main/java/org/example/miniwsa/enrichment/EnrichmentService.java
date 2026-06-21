package org.example.miniwsa.enrichment;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.example.miniwsa.event.Event;
import org.springframework.stereotype.Service;

/**
 * Orchestrates enrichment (Part 2): classify → count (repeat-offender) → score, for each event in a
 * batch. Composes the three enrichment units; the only thing it adds is the <b>processing order</b>.
 *
 * <p><b>Intra-batch ordering.</b> The batch is sorted by {@code (timestamp, eventId)}
 * before scoring, so the repeat-offender count is deterministic: events are added to the window in
 * event-time order, and the count (which includes the current event) makes a same-IP wave fire on
 * the 6th event — identically whether the wave arrived in order, shuffled, or split across batches.
 */
@Service
public class EnrichmentService {

    private final AttackTypeClassifier classifier;
    private final ThreatScorer scorer;
    private final RepeatOffenderDetector detector;

    public EnrichmentService(AttackTypeClassifier classifier, ThreatScorer scorer,
                             RepeatOffenderDetector detector) {
        this.classifier = classifier;
        this.scorer = scorer;
        this.detector = detector;
    }

    /** Enriches a batch in event-time order; the returned list is in that sorted order. */
    public List<EnrichedEvent> enrich(List<Event> events, Instant receivedAt) {
        return events.stream()
                .sorted(Comparator.comparing(Event::timestamp).thenComparing(Event::eventId))
                .map(event -> enrichOne(event, receivedAt))
                .toList();
    }

    private EnrichedEvent enrichOne(Event event, Instant receivedAt) {
        String attackType = classifier.classify(event.rule().category());
        boolean repeatOffender = detector.isRepeatOffender(event);
        int threatScore = scorer.score(event, repeatOffender);
        return new EnrichedEvent(event, receivedAt, attackType, threatScore);
    }
}
