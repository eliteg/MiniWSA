package org.example.miniwsa.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import org.example.miniwsa.event.Category;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Classification contract : every category maps to its assignment attackType. */
class AttackTypeClassifierTest {

    private final AttackTypeClassifier classifier = new AttackTypeClassifier();

    @ParameterizedTest
    @CsvSource({
        "INJECTION,          SQL/Command Injection",
        "XSS,                Cross-Site Scripting",
        "PROTOCOL_VIOLATION, Protocol Anomaly",
        "DATA_LEAKAGE,       Data Exfiltration",
        "BOT,                Bot Activity",
        "DOS,                Denial of Service",
        "RATE_LIMIT,         Rate Limiting"
    })
    void mapsEachCategoryToItsAttackType(Category category, String expected) {
        assertThat(classifier.classify(category)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(Category.class)
    void everyCategoryIsClassified(Category category) {
        assertThat(classifier.classify(category)).isNotBlank();
    }
}
