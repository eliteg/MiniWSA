package org.example.miniwsa.event;

/**
 * Attack category carried on the rule (assignment domain model). An unknown value is rejected
 * by Jackson at deserialization — that's how "valid enum value" is enforced (spec §8).
 */
public enum Category {
    INJECTION,
    XSS,
    PROTOCOL_VIOLATION,
    DATA_LEAKAGE,
    BOT,
    DOS,
    RATE_LIMIT
}
