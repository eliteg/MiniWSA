package org.example.miniwsa.alert;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;

public class AlertRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<AlertRule> ROW_MAPPER = (rs, n) -> new AlertRule(
            rs.getLong("id"),
            rs.getString("category"),
            rs.getInt("threshold"),
            rs.getInt("window_minutes"),
            rs.getTimestamp("created_at").toInstant());

    public AlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AlertRule save(AlertRuleRequest req) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO alert_rules (category, threshold, window_minutes) VALUES (?, ?, ?)",
                    new String[]{"id", "created_at"});
            ps.setString(1, req.category().name());
            ps.setInt(2, req.threshold());
            ps.setInt(3, req.windowMinutes());
            return ps;
        }, keyHolder);
        var keys = keyHolder.getKeys();
        return new AlertRule(
                ((Number) keys.get("id")).longValue(),
                req.category().name(),
                req.threshold(),
                req.windowMinutes(),
                ((java.sql.Timestamp) keys.get("created_at")).toInstant());
    }

    public List<AlertRule> findAll() {
        return jdbc.query("SELECT id, category, threshold, window_minutes, created_at FROM alert_rules ORDER BY id",
                ROW_MAPPER);
    }
}
