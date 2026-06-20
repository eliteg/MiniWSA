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
import org.example.miniwsa.event.Category;
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

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired private TestRestTemplate template;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearEvents() {
        jdbc.update("DELETE FROM events");
    }

    /**
     * Sends 10 events from the same IP within 10 minutes plus 1 background event,
     * all in a single POST so the enrichment service sorts by timestamp and applies
     * the repeat-offender bonus correctly.
     *
     * Scoring for MEDIUM + MONITOR, no sensitive path:
     *   base = 20 + 0 = 20
     *   events 1-5  → 20  (window not yet exceeded)
     *   events 6-10 → 35  (20 + 15 repeat-offender bonus)
     *   wave avg    = (5×20 + 5×35) / 10 = 27.5
     *   bg avg      = 20.0 (single event, no bonus)
     */
    @Test
    void waveIpGetsRepeatOffenderBonusVisibleInStats() throws Exception {
        Instant base   = Instant.now().minusSeconds(600);
        String  waveIp = "5.5.5.5";
        String  bgIp   = "9.8.7.6";

        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(buildEvent("wave-" + i, waveIp, base.plusSeconds(i * 60L)));
        }
        events.add(buildEvent("bg-0", bgIp, base));

        ResponseEntity<String> ingest = template.postForEntity(
                "/v1/events/ingest", events, String.class);
        assertThat(ingest.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // ── stats API ────────────────────────────────────────────────────────
        String rawStats = template.getForEntity("/v1/events/stats", String.class).getBody();
        StatsResponse stats = template.getForEntity("/v1/events/stats", StatsResponse.class).getBody();

        log.info("\n=== Stats raw response ===\n{}\n=========================",
                PRETTY.readTree(rawStats).toPrettyString());

        StringBuilder statsSummary = new StringBuilder("\n=== Stats summary ===\n");
        statsSummary.append(String.format("totalEvents: %d%n", stats.totalEvents()));
        statsSummary.append("topAttackers:\n");
        stats.topAttackers().forEach(a -> statsSummary.append(
                String.format("  %-15s  count=%-3d  avgThreatScore=%s%n",
                        a.clientIp(), a.count(), a.avgThreatScore())));
        statsSummary.append("byCategory:\n");
        stats.byCategory().forEach((cat, s) -> statsSummary.append(
                String.format("  %-20s  count=%-3d  avgThreatScore=%s%n",
                        cat, s.count(), s.avgThreatScore())));
        statsSummary.append("====================");
        log.info("{}", statsSummary);

        StatsResponse.AttackerStat waveAttacker = stats.topAttackers().get(0);
        assertThat(waveAttacker.clientIp()).isEqualTo(waveIp);
        assertThat(waveAttacker.count()).isEqualTo(10);
        assertThat(waveAttacker.avgThreatScore()).isGreaterThan(new BigDecimal("20.0"));

        StatsResponse.AttackerStat bgAttacker = stats.topAttackers().stream()
                .filter(a -> bgIp.equals(a.clientIp()))
                .findFirst().orElseThrow();
        assertThat(bgAttacker.avgThreatScore()).isEqualByComparingTo(new BigDecimal("20.0"));

        // ── samples API ──────────────────────────────────────────────────────
        String samplesUrl = "/v1/events/samples?category=BOT&limit=20&from=2000-01-01T00:00:00Z";
        String rawSamples = template.getForEntity(samplesUrl, String.class).getBody();
        SamplesResponse samples = template.getForEntity(samplesUrl, SamplesResponse.class).getBody();

        log.info("\n=== Samples raw response ===\n{}\n===========================",
                PRETTY.readTree(rawSamples).toPrettyString());

        StringBuilder samplesSummary = new StringBuilder("\n=== Samples summary ===\n");
        samplesSummary.append(String.format("total: %d%n", samples.total()));
        samples.events().forEach(e -> samplesSummary.append(
                String.format("  %-50s  %-15s  severity=%-8s  action=%-8s  threatScore=%d%n",
                        e.eventId(), e.clientIp(), e.severity(), e.action(), e.threatScore())));
        samplesSummary.append("======================");
        log.info("{}", samplesSummary);

        assertThat(samples.total()).isEqualTo(11);
        long bonusCount = samples.events().stream()
                .filter(e -> e.threatScore() > 20)
                .count();
        assertThat(bonusCount).isEqualTo(5);
    }

    @Test
    void batchWithOneInvalidEventIsFullyRejected() throws Exception {
        Instant base = Instant.now().minusSeconds(300);

        // ── happy flow first ─────────────────────────────────────────────────
        List<Map<String, Object>> validBatch = List.of(
                buildEvent("valid-0", "1.1.1.1", base),
                buildEvent("valid-1", "1.1.1.1", base.plusSeconds(60)));

        ResponseEntity<String> happy = template.postForEntity(
                "/v1/events/ingest", validBatch, String.class);
        log.info("\n=== Happy flow ingest response (expect 201) ===\n{}\n===============================================",
                PRETTY.readTree(happy.getBody()).toPrettyString());
        assertThat(happy.getStatusCode().value()).isEqualTo(201);

        log.info("\n=== Stats after valid batch ===\n{}\n==============================",
                PRETTY.readTree(template.getForEntity("/v1/events/stats", String.class).getBody()).toPrettyString());
        log.info("\n=== Samples after valid batch ===\n{}\n================================",
                PRETTY.readTree(template.getForEntity(
                        "/v1/events/samples?from=2000-01-01T00:00:00Z", String.class).getBody()).toPrettyString());

        StatsResponse statsAfterValid = template.getForEntity("/v1/events/stats", StatsResponse.class).getBody();
        assertThat(statsAfterValid.totalEvents()).isEqualTo(2);

        // ── now send batch with one invalid event ────────────────────────────
        log.info("\n{}", "=".repeat(80));
        log.info("PHASE 2 — invalid batch (one event missing 'action') — all-or-nothing rejection");
        log.info("{}\n", "=".repeat(80));
        jdbc.update("DELETE FROM events");

        List<Map<String, Object>> invalidBatch = new ArrayList<>();
        invalidBatch.add(buildEvent("valid-2", "1.1.1.1", base));
        invalidBatch.add(buildEvent("valid-3", "1.1.1.1", base.plusSeconds(60)));
        invalidBatch.add(Map.of(
                "eventId",   "repeat-test-bad-" + UUID.randomUUID(),
                "timestamp", base.plusSeconds(120).toString(),
                "configId",  1,
                "clientIp",  "1.1.1.1",
                "path",      "/api/v1/data",
                "rule",      Map.of("severity", "MEDIUM", "category", "BOT")
                // missing "action" — invalidates the whole batch
        ));

        ResponseEntity<String> rejected = template.postForEntity(
                "/v1/events/ingest", invalidBatch, String.class);
        log.info("\n=== Invalid batch ingest response (expect 400) ===\n{}\n=================================================",
                PRETTY.readTree(rejected.getBody()).toPrettyString());
        assertThat(rejected.getStatusCode().value()).isEqualTo(400);

        log.info("\n=== Stats after rejected batch (expect empty) ===\n{}\n================================================",
                PRETTY.readTree(template.getForEntity("/v1/events/stats", String.class).getBody()).toPrettyString());
        log.info("\n=== Samples after rejected batch (expect empty) ===\n{}\n==================================================",
                PRETTY.readTree(template.getForEntity(
                        "/v1/events/samples?from=2000-01-01T00:00:00Z", String.class).getBody()).toPrettyString());

        StatsResponse statsAfterRejected = template.getForEntity("/v1/events/stats", StatsResponse.class).getBody();
        assertThat(statsAfterRejected.totalEvents()).isZero();
        assertThat(statsAfterRejected.topAttackers()).isEmpty();
        SamplesResponse samplesAfterRejected = template.getForEntity(
                "/v1/events/samples?from=2000-01-01T00:00:00Z", SamplesResponse.class).getBody();
        assertThat(samplesAfterRejected.total()).isZero();
        assertThat(samplesAfterRejected.events()).isEmpty();
    }

    private static Map<String, Object> buildEvent(String suffix, String ip, Instant ts) {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("eventId",      "repeat-test-" + suffix + "-" + UUID.randomUUID());
        event.put("timestamp",    ts.toString());
        event.put("configId",     1);
        event.put("policyId",     "policy-1234");
        event.put("clientIp",     ip);
        event.put("hostname",     "api.example.com");
        event.put("path",         "/api/v1/data");
        event.put("method",       "POST");
        event.put("statusCode",   200);
        event.put("userAgent",    "curl/7.68.0");
        event.put("requestSize",  1024);
        event.put("responseSize", 256);
        event.put("geoLocation",  Map.of("country", "CN", "city", "Beijing"));
        event.put("rule", Map.of(
                "id",       Category.BOT.ruleId,
                "name",     Category.BOT.ruleName,
                "message",  Category.BOT.ruleMessage,
                "severity", "MEDIUM",
                "category", "BOT"));
        event.put("action", "MONITOR");
        return event;
    }
}
