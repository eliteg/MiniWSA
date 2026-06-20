package org.example.miniwsa.storage;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import org.example.miniwsa.enrichment.EnrichedEvent;
import org.example.miniwsa.event.Event;
import org.example.miniwsa.event.GeoLocation;
import org.example.miniwsa.event.Rule;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcEventStore implements EventStore {

    private static final String INSERT = """
            INSERT INTO events (
                event_id, timestamp, config_id, client_ip, path,
                category, severity, action, attack_type, threat_score, received_at,
                policy_id, hostname, method, status_code, user_agent,
                request_size, response_size,
                geo_country, geo_city,
                rule_id, rule_name, rule_message
            ) VALUES (
                ?, ?, ?, ?::inet, ?,
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?,
                ?, ?,
                ?, ?, ?
            )
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    JdbcEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveAll(List<EnrichedEvent> events) {
        jdbc.batchUpdate(INSERT, events, events.size(), (ps, enriched) -> bind(ps, enriched));
    }

    private void bind(PreparedStatement ps, EnrichedEvent enriched) throws java.sql.SQLException {
        Event e = enriched.event();
        Rule r = e.rule();
        GeoLocation geo = e.geoLocation();

        ps.setString(1, e.eventId());
        ps.setTimestamp(2, java.sql.Timestamp.from(e.timestamp()));
        ps.setLong(3, e.configId());
        ps.setString(4, e.clientIp());
        ps.setString(5, e.path());

        ps.setString(6, r.category().name());
        ps.setString(7, r.severity().name());
        ps.setString(8, e.action().name());
        ps.setString(9, enriched.attackType());
        ps.setInt(10, enriched.threatScore());
        ps.setTimestamp(11, java.sql.Timestamp.from(enriched.receivedAt()));

        setNullable(ps, 12, e.policyId(), Types.VARCHAR);
        setNullable(ps, 13, e.hostname(), Types.VARCHAR);
        setNullable(ps, 14, e.method(), Types.VARCHAR);
        setNullable(ps, 15, e.statusCode(), Types.INTEGER);
        setNullable(ps, 16, e.userAgent(), Types.VARCHAR);
        setNullable(ps, 17, e.requestSize(), Types.INTEGER);
        setNullable(ps, 18, e.responseSize(), Types.INTEGER);

        setNullable(ps, 19, geo != null ? geo.country() : null, Types.VARCHAR);
        setNullable(ps, 20, geo != null ? geo.city() : null, Types.VARCHAR);

        setNullable(ps, 21, r.id(), Types.VARCHAR);
        setNullable(ps, 22, r.name(), Types.VARCHAR);
        setNullable(ps, 23, r.message(), Types.VARCHAR);
    }

    private void setNullable(PreparedStatement ps, int idx, Object value, int sqlType)
            throws java.sql.SQLException {
        if (value == null) ps.setNull(idx, sqlType);
        else ps.setObject(idx, value);
    }
}
