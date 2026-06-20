package org.example.miniwsa.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.example.miniwsa.storage.EventStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * API contract for {@code POST /v1/events/ingest} (Part 1, spec §8.1): single + array,
 * {@code 201} on all-valid, {@code 400}-with-details on any invalid (all-or-nothing).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IngestControllerTest {

    @MockBean
    EventStore eventStore;

    @LocalServerPort
    private int port;

    // TestRestTemplate returns the response for 4xx instead of throwing — we assert on status.
    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate template;

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
                "http://localhost:" + port + "/v1/events/ingest",
                new HttpEntity<>(body, headers),
                String.class);
    }

    @Test
    void singleValidEventReturns201WithServerReceivedAt() {
        Instant before = Instant.now().minusSeconds(5);

        ResponseEntity<String> response = post(VALID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"accepted\":1").contains("\"receivedAt\":");
        // receivedAt is a recent, server-assigned instant
        Matcher m = Pattern.compile("\"receivedAt\":\"([^\"]+)\"").matcher(response.getBody());
        assertThat(m.find()).isTrue();
        assertThat(Instant.parse(m.group(1))).isAfter(before);
        // the event payload is NOT echoed back
        assertThat(response.getBody()).doesNotContain("eventId");
    }

    @Test
    void arrayOfValidEventsReturns201WithCount() {
        String body = "[" + VALID + "," + VALID.replace("evt-1", "evt-2") + "]";
        ResponseEntity<String> response = post(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"accepted\":2");
    }

    @Test
    void clientSuppliedReceivedAtIsIgnored() {
        String body = VALID.replace("\"action\": \"DENY\"",
                "\"action\": \"DENY\", \"receivedAt\": \"2000-01-01T00:00:00Z\"");
        ResponseEntity<String> response = post(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).doesNotContain("2000-01-01"); // server time, not the client's
    }

    @Test
    void singleInvalidEventReturns400WithDetails() {
        ResponseEntity<String> response = post(VALID.replace("evt-1", ""));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("validation failed").contains("eventId");
    }

    @Test
    void arrayWithOneInvalidRejectsWholeBatch() {
        String invalid = VALID.replace("evt-1", "").replace("\"path\": \"/api/v1/login\",", "");
        String body = "[" + VALID + "," + invalid + "]";
        ResponseEntity<String> response = post(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // all-or-nothing: the bad element is index 1, nothing accepted
        assertThat(response.getBody()).contains("\"index\":1").contains("eventId").contains("path");
        assertThat(response.getBody()).doesNotContain("accepted");
    }

    @Test
    void missingRuleReturns400() {
        String body = VALID.replace(
                "\"rule\": { \"severity\": \"CRITICAL\", \"category\": \"INJECTION\" },", "");
        ResponseEntity<String> response = post(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("validation failed").contains("rule");
    }

    @Test
    void missingRuleCategoryReturns400() {
        String body = VALID.replace(", \"category\": \"INJECTION\"", "");
        ResponseEntity<String> response = post(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("rule.category");
    }

    @Test
    void badEnumReturns400InUnifiedShapeWithFieldAndAllowedValues() {
        ResponseEntity<String> response = post(VALID.replace("INJECTION", "NOPE"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // same shape as validation failures: error + invalidEvents[{index, errors}]
        assertThat(response.getBody())
                .contains("validation failed")
                .contains("\"index\":0")
                .contains("NOPE")
                .contains("category")
                .contains("INJECTION"); // allowed-values list
    }

    @Test
    void arrayWithBadEnumReportsCorrectIndex() {
        String body = "[" + VALID + "," + VALID.replace("evt-1", "evt-2").replace("DENY", "NOPE") + "]";
        ResponseEntity<String> response = post(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"index\":1").contains("NOPE").contains("action");
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
