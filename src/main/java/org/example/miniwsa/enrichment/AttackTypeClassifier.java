package org.example.miniwsa.enrichment;

import org.example.miniwsa.event.Category;
import org.springframework.stereotype.Component;

/**
 * Maps a rule {@link Category} to the human-readable {@code attackType} .
 *
 * <p>Implemented as an exhaustive {@code switch} with no {@code default}: if a new
 * {@link Category} is ever added, this stops compiling until its attack type is defined — a
 * compile-time guarantee that every category is classified (a map would silently return null).
 */
@Component
public class AttackTypeClassifier {

    public String classify(Category category) {
        return switch (category) {
            case INJECTION -> "SQL/Command Injection";
            case XSS -> "Cross-Site Scripting";
            case PROTOCOL_VIOLATION -> "Protocol Anomaly";
            case DATA_LEAKAGE -> "Data Exfiltration";
            case BOT -> "Bot Activity";
            case DOS -> "Denial of Service";
            case RATE_LIMIT -> "Rate Limiting";
        };
    }
}
