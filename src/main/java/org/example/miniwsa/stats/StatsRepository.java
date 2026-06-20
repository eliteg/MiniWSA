package org.example.miniwsa.stats;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.example.miniwsa.stats.StatsResponse.AttackerStat;
import org.example.miniwsa.stats.StatsResponse.CategoryStat;
import org.example.miniwsa.stats.StatsResponse.PathStat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.annotation.Transactional;

public class StatsRepository {

    private final JdbcTemplate jdbc;

    public StatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    StatsResponse query(Long configId, Instant from, Instant to) {
        Timestamp tsFrom = Timestamp.from(from);
        Timestamp tsTo   = Timestamp.from(to);

        String where  = configId != null ? "config_id = ? AND timestamp BETWEEN ? AND ?"
                                         : "timestamp BETWEEN ? AND ?";
        Object[] args = configId != null ? new Object[]{configId, tsFrom, tsTo}
                                         : new Object[]{tsFrom, tsTo};

        long total = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE " + where, Long.class, args);

        Map<String, CategoryStat> byCategory = new LinkedHashMap<>();
        jdbc.query("SELECT category, count(*) AS cnt, round(avg(threat_score)::numeric,1) AS avg_score "
                + "FROM events WHERE " + where + " GROUP BY category",
                (RowCallbackHandler) rs -> byCategory.put(rs.getString("category"),
                        new CategoryStat(rs.getLong("cnt"), rs.getBigDecimal("avg_score"))),
                args);

        Map<String, Long> byAction = new LinkedHashMap<>();
        jdbc.query("SELECT action, count(*) AS cnt FROM events WHERE " + where + " GROUP BY action",
                (RowCallbackHandler) rs -> byAction.put(rs.getString("action"), rs.getLong("cnt")),
                args);

        List<AttackerStat> topAttackers = jdbc.query(
                "SELECT host(client_ip) AS ip, count(*) AS cnt, "
                + "round(avg(threat_score)::numeric,1) AS avg_score "
                + "FROM events WHERE " + where + " GROUP BY client_ip ORDER BY cnt DESC LIMIT 10",
                (rs, i) -> new AttackerStat(
                        rs.getString("ip"), rs.getLong("cnt"), rs.getBigDecimal("avg_score")),
                args);

        List<PathStat> topPaths = jdbc.query(
                "SELECT path, count(*) AS cnt FROM events WHERE " + where
                + " GROUP BY path ORDER BY cnt DESC LIMIT 10",
                (rs, i) -> new PathStat(rs.getString("path"), rs.getLong("cnt")),
                args);

        return new StatsResponse(total, byCategory, byAction, topAttackers, topPaths);
    }
}
