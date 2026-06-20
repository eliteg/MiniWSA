package org.example.miniwsa.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.util.ArrayList;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end async ingestion: POST /v2/events/ingest → Kafka → consumer → enrich → store.
 *
 * Verifies:
 *  - 202 on valid batch; event lands in DB with correct enrichment (threatScore, attackType)
 *  - 400 on invalid batch; nothing published, DB stays empty
 *  - Wave from same IP triggers repeat-offender bonus on the 6th event
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("async")
@Tag("async")
class KafkaIngestionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Value("${wsa.kafka.topic.ingest}") String ingestTopic;
    @Value("${wsa.kafka.topic.dlq}")    String dlqTopic;

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ConsumerFactory<String, String> consumerFactory;

    @BeforeEach
    void cleanDb() {
        jdbc.update("DELETE FROM events");
    }

    // ── scenario 1: single event enriched correctly ───────────────────────────

    @Test
    void eventPublishedToKafkaIsEnrichedAndStored() {
        String eventId = "kafka-test-" + UUID.randomUUID();

        ResponseEntity<String> response = post(List.of(botEvent(eventId, "1.2.3.4",
                Instant.now().minusSeconds(60))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("\"accepted\":1");

        // wait for consumer to enrich and store
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM events WHERE event_id = ?",
                        Long.class, eventId)).isEqualTo(1));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT category, threat_score, attack_type FROM events WHERE event_id = ?", eventId);
        assertThat(row.get("category")).isEqualTo("BOT");
        assertThat(row.get("attack_type")).isEqualTo("Bot Activity");
        assertThat((Integer) row.get("threat_score")).isEqualTo(20); // MEDIUM=20, MONITOR=+0
    }

    // ── scenario 2: invalid batch rejected, nothing stored ────────────────────

    @Test
    void invalidBatchIsRejectedAndNothingIsStored() throws InterruptedException {
        List<String> invalidBatch = new ArrayList<>();
        invalidBatch.add(botEvent("valid-" + UUID.randomUUID(), "1.2.3.4", Instant.now()));
        invalidBatch.add("""
                {
                  "eventId": "bad-%s",
                  "timestamp": "%s",
                  "configId": 1,
                  "clientIp": "1.2.3.4",
                  "path": "/api/data",
                  "rule": { "severity": "MEDIUM", "category": "BOT" }
                }
                """.formatted(UUID.randomUUID(), Instant.now()));  // missing 'action'

        ResponseEntity<String> response = post(invalidBatch);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("validation failed").contains("action");

        // wait to confirm nothing slipped through to Kafka
        Thread.sleep(2_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM events", Long.class)).isZero();
    }

    // ── scenario 3: wave from same IP triggers repeat-offender bonus ──────────

    @Test
    void waveFromSameIpTriggersRepeatOffenderBonusOnSixthEvent() {
        String ip = "5.5.5.5";
        Instant base = Instant.now().minusSeconds(300);
        List<String> ids = new ArrayList<>();

        // 6 events from same IP — events 1-5 should score 20, event 6 should score 35
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String id = "wave-" + i + "-" + UUID.randomUUID();
            ids.add(id);
            events.add(botEvent(id, ip, base.plusSeconds(i * 30L)));
        }

        ResponseEntity<String> response = post(events);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("\"accepted\":6");

        // wait for all 6 to be stored
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM events", Long.class))
                        .isEqualTo(6));

        // events 1-5: base score 20; event 6: 20 + 15 repeat-offender bonus = 35
        long below = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE threat_score = 20", Long.class);
        long bonus = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE threat_score = 35", Long.class);
        assertThat(below).isEqualTo(5);
        assertThat(bonus).isEqualTo(1);
    }

    // ── scenario 4: malformed message is routed to DLQ ───────────────────────

    @Test
    void malformedMessageIsRoutedToDlq() throws Exception {
        TopicPartition dlqPartition = new TopicPartition(dlqTopic, 0);
        Consumer<String, String> dlqConsumer = consumerFactory.createConsumer(
                "dlq-test-" + UUID.randomUUID(), null);
        dlqConsumer.assign(List.of(dlqPartition));
        dlqConsumer.seekToEnd(List.of(dlqPartition));
        dlqConsumer.poll(Duration.ZERO); // force position initialisation

        kafkaTemplate.send(ingestTopic, "bad-key", "{ not valid json %%%").get();

        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            dlqConsumer.poll(Duration.ofMillis(200)).forEach(received::add);
            assertThat(received).isNotEmpty();
        });
        dlqConsumer.close();

        assertThat(received.get(0).value()).contains("not valid json");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM events", Long.class)).isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<String> post(List<String> eventJsons) {
        String body = "[" + String.join(",", eventJsons) + "]";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/v2/events/ingest",
                new HttpEntity<>(body, headers), String.class);
    }

    private static String botEvent(String eventId, String ip, Instant ts) {
        return """
                {
                  "eventId": "%s",
                  "timestamp": "%s",
                  "configId": 1,
                  "clientIp": "%s",
                  "path": "/api/data",
                  "rule": { "severity": "MEDIUM", "category": "BOT" },
                  "action": "MONITOR"
                }
                """.formatted(eventId, ts, ip);
    }
}
