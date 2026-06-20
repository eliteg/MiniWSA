package org.example.miniwsa.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the severity/action threat-score weights to the assignment's fixed contract (spec §6).
 * If anyone edits a weight away from the spec, this fails loudly.
 */
class ScoringWeightsTest {

    @Test
    void severityWeightsMatchAssignment() {
        assertThat(Severity.CRITICAL.weight()).isEqualTo(40);
        assertThat(Severity.HIGH.weight()).isEqualTo(30);
        assertThat(Severity.MEDIUM.weight()).isEqualTo(20);
        assertThat(Severity.LOW.weight()).isEqualTo(10);
    }

    @Test
    void actionAddOnsMatchAssignment() {
        assertThat(Action.DENY.points()).isEqualTo(20);
        assertThat(Action.ALERT.points()).isEqualTo(10);
        assertThat(Action.MONITOR.points()).isEqualTo(0);
    }
}
