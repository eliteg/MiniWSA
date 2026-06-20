package org.example.miniwsa.enrichment;

import java.util.List;
import org.example.miniwsa.event.Event;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Computes the threat score:
 *
 * <pre>severity weight + action add-on + sensitive-path bonus + repeat-offender bonus, capped.</pre>
 *
 * <p>The weights live on the {@link org.example.miniwsa.event.Severity}/
 * {@link org.example.miniwsa.event.Action} enums (fixed contract). The bonuses and sensitive
 * paths are constants. The <b>cap</b> is the one configurable knob ({@code wsa.scoring.cap},
 * default 100) — a policy ceiling a deployment may tune (e.g. a k8s ConfigMap / env var), with
 * {@code Math.min(raw, cap)} guaranteeing the result never exceeds whatever is configured.
 *
 * <p>{@code repeatOffender} is supplied by the repeat-offender window ; this class is a
 * pure function of its inputs.
 */
@Component
public class ThreatScorer {

    /** Literal-substring match, per the assignment (spec §6) — the leading slash anchors it. */
    private static final List<String> SENSITIVE_PATHS = List.of("/admin", "/login");
    private static final int SENSITIVE_PATH_BONUS = 15;
    private static final int REPEAT_OFFENDER_BONUS = 15;

    private final int cap;

    public ThreatScorer(@Value("${wsa.scoring.cap:100}") int cap) {
        this.cap = cap;
    }

    public int score(Event event, boolean repeatOffender) {
        int raw = event.rule().severity().weight()
                + event.action().points()
                + sensitivePathBonus(event.path())
                + (repeatOffender ? REPEAT_OFFENDER_BONUS : 0);
        return Math.min(raw, cap);
    }

    private int sensitivePathBonus(String path) {
        if (path == null) {
            return 0;
        }
        return SENSITIVE_PATHS.stream().anyMatch(path::contains) ? SENSITIVE_PATH_BONUS : 0;
    }
}
