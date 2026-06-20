package org.example.miniwsa.kafka;

import java.time.Instant;
import org.example.miniwsa.event.Event;

/** Kafka message envelope — carries the event and the receivedAt timestamp set at publish time. */
public record KafkaEventMessage(Instant receivedAt, Event event) {}
