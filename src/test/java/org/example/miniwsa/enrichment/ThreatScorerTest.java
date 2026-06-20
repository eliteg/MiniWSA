package org.example.miniwsa.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.example.miniwsa.event.Action;
import org.example.miniwsa.event.Category;
import org.example.miniwsa.event.Event;
import org.example.miniwsa.event.Rule;
import org.example.miniwsa.event.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Threat-score contract (Part 2 / spec §6): each component, the sensitive-path rule, and the cap. */
class ThreatScorerTest {

    private final ThreatScorer scorer = new ThreatScorer(100);

    private Event event(Severity severity, Action action, String path) {
        return new Event("e1", Instant.parse("2026-05-20T14:32:10Z"), 1L, null, "1.2.3.4", null,
                path, null, null, null, new Rule(null, null, null, severity, Category.INJECTION),
                action, null, null, null);
    }

    @Test
    void severityIsTheBaseWeight() {
        assertThat(scorer.score(event(Severity.CRITICAL, Action.MONITOR, "/x"), false)).isEqualTo(40);
        assertThat(scorer.score(event(Severity.LOW, Action.MONITOR, "/x"), false)).isEqualTo(10);
    }

    @Test
    void actionAddsOnTop() {
        assertThat(scorer.score(event(Severity.LOW, Action.DENY, "/x"), false)).isEqualTo(30);  // 10+20
        assertThat(scorer.score(event(Severity.LOW, Action.ALERT, "/x"), false)).isEqualTo(20); // 10+10
    }

    @Test
    void repeatOffenderAddsFifteen() {
        assertThat(scorer.score(event(Severity.LOW, Action.MONITOR, "/x"), true)).isEqualTo(25);  // 10+15
    }

    @ParameterizedTest
    @CsvSource({
        "/admin,         15",  // exact
        "/login,         15",  // exact
        "/api/v1/login,  15",  // tail segment
        "/x/admin/y,     15",  // middle segment
        "/administrator, 15",  // accepted tail over-match (§6)
        "/relogin,       0",   // leading slash before 're', not 'login'
        "/myadmin,       0",   // leading slash before 'my', not 'admin'
        "/home,          0"    // unrelated
    })
    void sensitivePathBonusIsLiteralContains(String path, int expectedBonus) {
        // LOW(10) + MONITOR(0) + pathBonus + no repeat-offender
        assertThat(scorer.score(event(Severity.LOW, Action.MONITOR, path), false))
                .isEqualTo(10 + expectedBonus);
    }

    @Test
    void maxReachableScoreIs90() {
        // CRITICAL(40) + DENY(20) + sensitive path(15) + repeat-offender(15)
        assertThat(scorer.score(event(Severity.CRITICAL, Action.DENY, "/admin"), true)).isEqualTo(90);
    }

    @Test
    void capClampsTheScore() {
        ThreatScorer cappedAt50 = new ThreatScorer(50);
        // raw would be 90, but the configured cap clamps it
        assertThat(cappedAt50.score(event(Severity.CRITICAL, Action.DENY, "/admin"), true)).isEqualTo(50);
    }
}
