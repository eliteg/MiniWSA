package org.example.miniwsa.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.example.miniwsa.alert.AlertResult;
import org.example.miniwsa.event.Category;
import org.example.miniwsa.samples.SampledEvent;
import org.example.miniwsa.samples.SamplesResponse;
import org.example.miniwsa.stats.StatsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RepeatOffenderIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RepeatOffenderIntegrationTest.class);
    private static final ObjectMapper PRETTY = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final String RUN_ID = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM alert_rules");
    }

    /**
     * Scenario 1 — Wave / burst detection
     *
     * Ingests 10 BOT events from 5.5.5.5 (wave) + 1 background event from 9.8.7.6.
     * Events are 1 minute apart, starting 10 minutes ago.
     *
     * Expected scoring (MEDIUM + MONITOR, no sensitive path):
     *   base score       = 20
     *   events 1-5  (5.5.5.5) → 20   (repeat-offender window not yet exceeded)
     *   events 6-10 (5.5.5.5) → 35   (20 + 15 repeat-offender bonus)
     *   wave avg         = (5×20 + 5×35) / 10 = 27.5
     *   background avg   = 20.0
     */
    @Test
    void scenario1_waveBurstDetection() throws Exception {
        String waveIp = "5.5.5.5";
        String bgIp   = "9.8.7.6";
        Instant base  = Instant.now().minusSeconds(600);
        List<String> savedFiles = new ArrayList<>();

        log.info("{}", title("SCENARIO 1 — Wave/burst detection: 10 events from " + waveIp));

        // ── ingest ───────────────────────────────────────────────────────────
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(buildBotEvent("wave-" + i, waveIp, base.plusSeconds(i * 60L)));
        }
        events.add(buildBotEvent("bg-0", bgIp, base));

        log.info("  → POST /v1/events/ingest  [11 BOT events: 10 wave + 1 background]");
        ResponseEntity<String> ingest = http.postForEntity("/v1/events/ingest", events, String.class);
        assertThat(ingest.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        log.info("{}", title("POST /v1/events/ingest"));
        log.info("{}", subtitle("Response", PRETTY.readTree(ingest.getBody()).toPrettyString()));

        // ── stats ─────────────────────────────────────────────────────────────
        log.info("{}", title("GET  /v1/events/stats"));
        String rawStats = http.getForEntity("/v1/events/stats", String.class).getBody();
        StatsResponse stats = PRETTY.readValue(rawStats, StatsResponse.class);
        savedFiles.add(save("scenario-1", "stats", rawStats));

        log.info("{}", subtitle("Response", PRETTY.readTree(rawStats).toPrettyString()));

        assertThat(stats.totalEvents()).isEqualTo(11);
        StatsResponse.AttackerStat wave = stats.topAttackers().get(0);
        assertThat(wave.clientIp()).isEqualTo(waveIp);
        assertThat(wave.count()).isEqualTo(10);
        assertThat(wave.avgThreatScore()).isGreaterThan(new BigDecimal("20.0"));
        StatsResponse.AttackerStat bg = stats.topAttackers().stream()
                .filter(a -> bgIp.equals(a.clientIp())).findFirst().orElseThrow();
        assertThat(bg.avgThreatScore()).isEqualByComparingTo(new BigDecimal("20.0"));

        // ── samples — paginated (limit=2) ─────────────────────────────────────
        log.info("{}", title(
                "GET /v1/events/samples?category={category}&limit={limit}&offset={offset}&from={ISO8601}"));

        int limit = 2;
        List<SampledEvent> allSampled = new ArrayList<>();
        List<String> pageUrls = new ArrayList<>();
        int offset = 0;
        long totalEvents = 0;
        while (true) {
            String url = "/v1/events/samples?category=BOT&limit=" + limit
                    + "&offset=" + offset + "&from=2000-01-01T00:00:00Z";
            pageUrls.add(url);
            String raw = http.getForEntity(url, String.class).getBody();
            SamplesResponse page = PRETTY.readValue(raw, SamplesResponse.class);
            if (offset == 0) totalEvents = page.total();
            savedFiles.add(save("scenario-1", "samples-p" + pageUrls.size(), raw));
            allSampled.addAll(page.events());
            if (page.events().size() < limit) break;
            offset += limit;
        }

        StringBuilder pageBlock = new StringBuilder();
        for (int i = 0; i < pageUrls.size(); i++) {
            pageBlock.append(String.format("  page %-2d → GET %s%n", i + 1, pageUrls.get(i)));
        }
        log.info("{}", subtitle(
                pageUrls.size() + " pages  (limit=" + limit + ", total=" + totalEvents + ")",
                pageBlock.toString().stripTrailing()));

        assertThat(totalEvents).isEqualTo(11);
        assertThat(allSampled.stream().filter(e -> e.threatScore() > 20).count()).isEqualTo(5);

        // ── alerts ────────────────────────────────────────────────────────────
        log.info("{}", title("POST /v1/alerts/define  +  GET /v1/alerts/evaluate"));

        log.info("  → POST /v1/alerts/define  [category=BOT  threshold=5  windowMinutes=20]");
        http.postForEntity("/v1/alerts/define",
                Map.of("category", "BOT", "threshold", 5, "windowMinutes", 20),
                String.class);

        log.info("  → GET  /v1/alerts/evaluate");
        String rawAlerts = http.getForEntity("/v1/alerts/evaluate", String.class).getBody();
        List<AlertResult> alerts = PRETTY.readValue(rawAlerts,
                PRETTY.getTypeFactory().constructCollectionType(List.class, AlertResult.class));
        savedFiles.add(save("scenario-1", "alerts", rawAlerts));

        log.info("{}", subtitle("Response", PRETTY.readTree(rawAlerts).toPrettyString()));

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).category()).isEqualTo("BOT");
        assertThat(alerts.get(0).currentCount()).isEqualTo(11);
        assertThat(alerts.get(0).firing()).isTrue();

        log.info("{}", subtitle("Saved files", String.join("\n", savedFiles)));
    }

    /**
     * Scenario 2 — Invalid batch is fully rejected (all-or-nothing)
     *
     * Phase 1: ingest 2 valid events — verify they are stored.
     * Phase 2: ingest a batch of 3 where one is missing 'action' — expect 400,
     *          verify nothing is stored (stats and samples remain empty).
     */
    @Test
    void scenario2_invalidBatchIsFullyRejected() throws Exception {
        Instant base = Instant.now().minusSeconds(300);
        List<String> savedFiles = new ArrayList<>();

        // ── phase 1: valid batch ──────────────────────────────────────────────
        log.info("{}", title("SCENARIO 2 — Phase 1: valid batch (expect 201)"));

        List<Map<String, Object>> valid = List.of(
                buildBotEvent("v0", "1.1.1.1", base),
                buildBotEvent("v1", "1.1.1.1", base.plusSeconds(60)));

        log.info("  → POST /v1/events/ingest  [2 valid BOT events]");
        ResponseEntity<String> ok = http.postForEntity("/v1/events/ingest", valid, String.class);
        assertThat(ok.getStatusCode().value()).isEqualTo(201);
        log.info("{}", title("POST /v1/events/ingest"));
        log.info("{}", subtitle("Response", PRETTY.readTree(ok.getBody()).toPrettyString()));

        log.info("{}", title("GET  /v1/events/stats"));
        String rawStats1 = http.getForEntity("/v1/events/stats", String.class).getBody();
        savedFiles.add(save("scenario-2/phase-1", "stats", rawStats1));
        log.info("{}", subtitle("Response", PRETTY.readTree(rawStats1).toPrettyString()));
        assertThat(PRETTY.readValue(rawStats1, StatsResponse.class).totalEvents()).isEqualTo(2);

        log.info("{}", title("GET  /v1/events/samples?from={ISO8601}"));
        String rawSamples1 = http.getForEntity(
                "/v1/events/samples?from=2000-01-01T00:00:00Z", String.class).getBody();
        savedFiles.add(save("scenario-2/phase-1", "samples", rawSamples1));
        log.info("{}", subtitle("Response", PRETTY.readTree(rawSamples1).toPrettyString()));
        assertThat(PRETTY.readValue(rawSamples1, SamplesResponse.class).total()).isEqualTo(2);

        // ── phase 2: invalid batch ────────────────────────────────────────────
        jdbc.update("DELETE FROM events");
        log.info("{}", title("SCENARIO 2 — Phase 2: invalid batch (expect 400, nothing stored)"));

        List<Map<String, Object>> invalid = new ArrayList<>();
        invalid.add(buildBotEvent("v2", "1.1.1.1", base));
        invalid.add(buildBotEvent("v3", "1.1.1.1", base.plusSeconds(60)));
        invalid.add(Map.of(                                    // missing 'action'
                "eventId",   "bad-" + UUID.randomUUID(),
                "timestamp", base.plusSeconds(120).toString(),
                "configId",  1,
                "clientIp",  "1.1.1.1",
                "path",      "/api/v1/data",
                "rule",      Map.of("severity", "MEDIUM", "category", "BOT")));

        log.info("  → POST /v1/events/ingest  [3 events, index 2 missing 'action']");
        ResponseEntity<String> bad = http.postForEntity("/v1/events/ingest", invalid, String.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        log.info("{}", title("POST /v1/events/ingest"));
        log.info("{}", subtitle("Response", PRETTY.readTree(bad.getBody()).toPrettyString()));

        log.info("{}", title("GET  /v1/events/stats  [expect empty]"));
        String rawStats2 = http.getForEntity("/v1/events/stats", String.class).getBody();
        savedFiles.add(save("scenario-2/phase-2", "stats", rawStats2));
        log.info("{}", subtitle("Response", PRETTY.readTree(rawStats2).toPrettyString()));

        log.info("{}", title("GET  /v1/events/samples?from={ISO8601}  [expect empty]"));
        String rawSamples2 = http.getForEntity(
                "/v1/events/samples?from=2000-01-01T00:00:00Z", String.class).getBody();
        savedFiles.add(save("scenario-2/phase-2", "samples", rawSamples2));
        log.info("{}", subtitle("Response", PRETTY.readTree(rawSamples2).toPrettyString()));

        StatsResponse statsAfter = PRETTY.readValue(rawStats2, StatsResponse.class);
        SamplesResponse samplesAfter = PRETTY.readValue(rawSamples2, SamplesResponse.class);
        assertThat(statsAfter.totalEvents()).isZero();
        assertThat(statsAfter.topAttackers()).isEmpty();
        assertThat(samplesAfter.total()).isZero();
        assertThat(samplesAfter.events()).isEmpty();

        log.info("{}", subtitle("Saved files", String.join("\n", savedFiles)));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String title(String text) {
        String bar = "*".repeat(text.length() + 8);
        return "\n" + bar + "\n    " + text + "\n" + bar;
    }

    private static String subtitle(String label, String body) {
        String bar = "=".repeat(label.length() + 8);
        return "\n  " + label + "\n" + bar + "\n" + body + "\n" + bar;
    }

    private static String save(String scenario, String name, String rawJson) throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of("data/test-runs", RUN_ID, scenario);
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Path file = dir.resolve(name + ".json");
        java.nio.file.Files.writeString(file, PRETTY.readTree(rawJson).toPrettyString());
        return file.toString();
    }

    private static Map<String, Object> buildBotEvent(String suffix, String ip, Instant ts) {
        Map<String, Object> e = new java.util.LinkedHashMap<>();
        e.put("eventId",      "test-" + suffix + "-" + UUID.randomUUID());
        e.put("timestamp",    ts.toString());
        e.put("configId",     1);
        e.put("policyId",     "policy-1234");
        e.put("clientIp",     ip);
        e.put("hostname",     "api.example.com");
        e.put("path",         "/api/v1/data");
        e.put("method",       "POST");
        e.put("statusCode",   200);
        e.put("userAgent",    "curl/7.68.0");
        e.put("requestSize",  1024);
        e.put("responseSize", 256);
        e.put("geoLocation",  Map.of("country", "CN", "city", "Beijing"));
        e.put("rule", Map.of(
                "id",       Category.BOT.ruleId,
                "name",     Category.BOT.ruleName,
                "message",  Category.BOT.ruleMessage,
                "severity", "MEDIUM",
                "category", "BOT"));
        e.put("action", "MONITOR");
        return e;
    }
}
