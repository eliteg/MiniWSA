package org.example.miniwsa.alert;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public class AlertEvaluator {

    private final JdbcTemplate jdbc;

    public AlertEvaluator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AlertResult> evaluate() {
        return jdbc.query(
                """
                SELECT ar.id, ar.category, ar.threshold, ar.window_minutes, ar.created_at,
                       count(e.event_id) AS current_count
                FROM alert_rules ar
                LEFT JOIN events e
                  ON  e.category  = ar.category
                  AND e.timestamp >= now() - (ar.window_minutes * interval '1 minute')
                GROUP BY ar.id, ar.category, ar.threshold, ar.window_minutes, ar.created_at
                ORDER BY ar.id
                """,
                (rs, n) -> {
                    long current = rs.getLong("current_count");
                    int threshold = rs.getInt("threshold");
                    return new AlertResult(
                            rs.getLong("id"),
                            rs.getString("category"),
                            threshold,
                            rs.getInt("window_minutes"),
                            current,
                            current > threshold);
                });
    }
}
