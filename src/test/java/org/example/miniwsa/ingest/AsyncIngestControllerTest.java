package org.example.miniwsa.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.example.miniwsa.alert.AlertEvaluator;
import org.example.miniwsa.alert.AlertRepository;
import org.example.miniwsa.samples.SamplesRepository;
import org.example.miniwsa.stats.StatsRepository;
import org.example.miniwsa.storage.EventStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * API contract for {@code POST /v2/events/ingest} (async profile): same validation rules as v1,
 * {@code 202 Accepted} on all-valid (published to Kafka), {@code 400} on any invalid.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("async")
class AsyncIngestControllerTest {

    @MockBean EventStore eventStore;
    @MockBean StatsRepository statsRepository;
    @MockBean SamplesRepository samplesRepository;
    @MockBean AlertRepository alertRepository;
    @MockBean AlertEvaluator alertEvaluator;
    @MockBean KafkaTemplate<String, String> kafka;

    @LocalServerPort int port;
    @Autowired TestRestTemplate template;

    private static final String VALID = """
            {
              "eventId": "evt-1",
              "timestamp": "2026-05-20T14:32:10Z",
              "configId": 14227,
              "clientIp": "203.0.113.42",
              "path": "/api/v1/login",
              "rule": { "severity": "CRITICAL", "category": "INJECTION" },
              "action": "DENY"
            }
            """;

    private ResponseEntity<String> post(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return template.postForEntity(
                "http://localhost:" + port + "/v2/events/ingest",
                new HttpEntity<>(body, headers),
                String.class);
    }

    @Test
    void singleValidEventReturns202WithServerReceivedAt() {
        Instant before = Instant.now().minusSeconds(5);

        ResponseEntity<String> response = post(VALID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("\"accepted\":1").contains("\"receivedAt\":");
        Matcher m = Pattern.compile("\"receivedAt\":\"([^\"]+)\"").matcher(response.getBody());
        assertThat(m.find()).isTrue();
        assertThat(Instant.parse(m.group(1))).isAfter(before);
        assertThat(response.getBody()).doesNotContain("eventId");
    }

    @Test
    void arrayOfValidEventsReturns202WithCount() {
        String body = "[" + VALID + "," + VALID.replace("evt-1", "evt-2") + "]";
        ResponseEntity<String> response = post(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("\"accepted\":2");
    }

    @Test
    void validEventsArePublishedToKafka() {
        String body = "[" + VALID + "," + VALID.replace("evt-1", "evt-2") + "]";
        post(body);
        verify(kafka, times(2)).send(any(), any(), any());
    }

    @Test
    void singleInvalidEventReturns400WithDetails() {
        ResponseEntity<String> response = post(VALID.replace("evt-1", ""));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("validation failed").contains("eventId");
    }

    @Test
    void invalidBatchPublishesNothingToKafka() {
        post(VALID.replace("evt-1", ""));
        verify(kafka, never()).send(any(), any(), any());
    }

    @Test
    void arrayWithOneInvalidRejectsWholeBatch() {
        String invalid = VALID.replace("evt-1", "").replace("\"path\": \"/api/v1/login\",", "");
        String body = "[" + VALID + "," + invalid + "]";
        ResponseEntity<String> response = post(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"index\":1").contains("eventId").contains("path");
        assertThat(response.getBody()).doesNotContain("accepted");
    }

    @Test
    void badEnumReturns400WithFieldAndAllowedValues() {
        ResponseEntity<String> response = post(VALID.replace("INJECTION", "NOPE"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .contains("validation failed")
                .contains("\"index\":0")
                .contains("NOPE")
                .contains("category")
                .contains("INJECTION");
    }

    @Test
    void badTimestampReturns400WithField() {
        ResponseEntity<String> response = post(VALID.replace("2026-05-20T14:32:10Z", "nope"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("validation failed").contains("timestamp");
    }

    @Test
    void malformedJsonReturns400WithEmptyInvalidEvents() {
        ResponseEntity<String> response = post("{ not valid json ");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("malformed JSON").contains("\"invalidEvents\":[]");
    }
}
