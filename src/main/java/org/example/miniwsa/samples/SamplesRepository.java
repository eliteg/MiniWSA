package org.example.miniwsa.samples;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class SamplesRepository {

    private final JdbcTemplate jdbc;

    public SamplesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public SamplesResponse query(Long configId, Instant from, Instant to,
                                 String category, String action, int limit, int offset) {
        List<String> conditions = new ArrayList<>();
        List<Object> filterArgs = new ArrayList<>();

        if (configId != null) { conditions.add("config_id = ?");  filterArgs.add(configId); }
        if (from != null)     { conditions.add("timestamp >= ?"); filterArgs.add(Timestamp.from(from)); }
        if (to != null)       { conditions.add("timestamp <= ?"); filterArgs.add(Timestamp.from(to)); }
        if (category != null) { conditions.add("category = ?");   filterArgs.add(category); }
        if (action != null)   { conditions.add("action = ?");     filterArgs.add(action); }

        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);

        Object[] countArgs = filterArgs.toArray();
        List<Object> pageArgsList = new ArrayList<>(filterArgs);
        pageArgsList.add(limit);
        pageArgsList.add(offset);
        Object[] pageArgs = pageArgsList.toArray();

        long total = jdbc.queryForObject(
                "SELECT count(*) FROM events " + where, Long.class, countArgs);

        List<SampledEvent> events = jdbc.query(
                "SELECT event_id, timestamp, config_id, host(client_ip) AS client_ip, path, "
                + "category, severity, action, attack_type, threat_score, received_at, "
                + "policy_id, hostname, method, status_code, user_agent, "
                + "request_size, response_size, geo_country, geo_city, "
                + "rule_id, rule_name, rule_message "
                + "FROM events " + where
                + " ORDER BY timestamp DESC LIMIT ? OFFSET ?",
                (rs, i) -> mapRow(rs),
                pageArgs);

        return new SamplesResponse(total, limit, offset, events);
    }

    private SampledEvent mapRow(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("timestamp");
        Timestamp ra = rs.getTimestamp("received_at");
        return new SampledEvent(
                rs.getString("event_id"),
                ts != null ? ts.toInstant() : null,
                rs.getLong("config_id"),
                rs.getString("client_ip"),
                rs.getString("path"),
                rs.getString("category"),
                rs.getString("severity"),
                rs.getString("action"),
                rs.getString("attack_type"),
                rs.getInt("threat_score"),
                ra != null ? ra.toInstant() : null,
                rs.getString("policy_id"),
                rs.getString("hostname"),
                rs.getString("method"),
                (Integer) rs.getObject("status_code"),
                rs.getString("user_agent"),
                (Integer) rs.getObject("request_size"),
                (Integer) rs.getObject("response_size"),
                rs.getString("geo_country"),
                rs.getString("geo_city"),
                rs.getString("rule_id"),
                rs.getString("rule_name"),
                rs.getString("rule_message"));
    }
}
