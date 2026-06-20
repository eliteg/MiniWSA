package org.example.miniwsa.stats;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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

        // Query 1: byCategory + byAction + grand total in one scan via GROUPING SETS.
        // Row shapes:
        //   category=X, action=null → per-category  (grouping set (category))
        //   category=null, action=Y → per-action     (grouping set (action))
        //   category=null, action=null → grand total (grouping set ())
        Map<String, CategoryStat> byCategory = new LinkedHashMap<>();
        Map<String, Long>         byAction   = new LinkedHashMap<>();
        long[]                    totalHolder = {0};

        jdbc.query(
                "SELECT category, action, count(*) AS cnt, "
                + "round(avg(threat_score)::numeric,1) AS avg_score "
                + "FROM events WHERE " + where
                + " GROUP BY GROUPING SETS ((category), (action), ())",
                (RowCallbackHandler) rs -> {
                    String cat = rs.getString("category");
                    String act = rs.getString("action");
                    long   cnt = rs.getLong("cnt");
                    if (cat == null && act == null) {
                        totalHolder[0] = cnt;
                    } else if (act == null) {
                        byCategory.put(cat, new CategoryStat(cnt, rs.getBigDecimal("avg_score")));
                    } else {
                        byAction.put(act, cnt);
                    }
                },
                args);

        long total = totalHolder[0];

        // Query 2: top-10 attackers + top-10 paths in one scan via UNION ALL subqueries.
        // args duplicated: first copy for the attacker subquery, second for the path subquery.
        Object[] doubleArgs = configId != null
                ? new Object[]{configId, tsFrom, tsTo, configId, tsFrom, tsTo}
                : new Object[]{tsFrom, tsTo, tsFrom, tsTo};

        List<AttackerStat> topAttackers = new ArrayList<>();
        List<PathStat>     topPaths     = new ArrayList<>();

        jdbc.query(
                "SELECT * FROM ("
                + "  SELECT 'attacker' AS kind, host(client_ip) AS key, count(*) AS cnt,"
                + "         round(avg(threat_score)::numeric,1) AS avg_score"
                + "  FROM events WHERE " + where
                + "  GROUP BY client_ip ORDER BY cnt DESC LIMIT 10"
                + ") a"
                + " UNION ALL"
                + " SELECT * FROM ("
                + "  SELECT 'path' AS kind, path AS key, count(*) AS cnt, NULL::numeric AS avg_score"
                + "  FROM events WHERE " + where
                + "  GROUP BY path ORDER BY cnt DESC LIMIT 10"
                + ") p",
                (RowCallbackHandler) rs -> {
                    String kind = rs.getString("kind");
                    String key  = rs.getString("key");
                    long   cnt  = rs.getLong("cnt");
                    if ("attacker".equals(kind)) {
                        topAttackers.add(new AttackerStat(key, cnt, rs.getBigDecimal("avg_score")));
                    } else {
                        topPaths.add(new PathStat(key, cnt));
                    }
                },
                doubleArgs);

        return new StatsResponse(total, byCategory, byAction, topAttackers, topPaths);
    }
}
