package org.example.miniwsa.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Validation contract for the {@link Event} DTO (spec §3, §8). Two mechanisms are exercised:
 * Jackson (enum + timestamp format) and Bean Validation (required fields).
 */
class EventValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final String VALID_JSON = """
            {
              "eventId": "evt-00132",
              "timestamp": "2026-05-20T14:32:10Z",
              "configId": 14227,
              "clientIp": "203.0.113.42",
              "path": "/api/v1/login",
              "rule": { "severity": "CRITICAL", "category": "INJECTION" },
              "action": "DENY"
            }
            """;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private Set<ConstraintViolation<Event>> validate(String json) throws Exception {
        return validator.validate(mapper.readValue(json, Event.class));
    }

    private boolean violatedField(Set<ConstraintViolation<Event>> violations, String path) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(path));
    }

    // --- valid ---------------------------------------------------------------

    @Test
    void validEventPassesValidation() throws Exception {
        assertThat(validate(VALID_JSON)).isEmpty();
    }

    @Test
    void serverAssignedFieldsAreIgnoredNotRejected() throws Exception {
        String json = VALID_JSON.replace(
                "\"action\": \"DENY\"",
                "\"action\": \"DENY\", \"receivedAt\": \"2026-01-01T00:00:00Z\", \"threatScore\": 999");
        assertThat(validate(json)).isEmpty();
    }

    // --- invalid: required fields (Bean Validation) --------------------------

    @Test
    void blankEventIdFails() throws Exception {
        assertThat(violatedField(validate(VALID_JSON.replace("evt-00132", "")), "eventId")).isTrue();
    }

    @Test
    void missingPathFails() throws Exception {
        assertThat(violatedField(validate(VALID_JSON.replace("\"path\": \"/api/v1/login\",", "")), "path"))
                .isTrue();
    }

    @Test
    void missingConfigIdFails() throws Exception {
        assertThat(violatedField(validate(VALID_JSON.replace("\"configId\": 14227,", "")), "configId"))
                .isTrue();
    }

    @Test
    void missingRuleSeverityFails() throws Exception {
        assertThat(violatedField(validate(VALID_JSON.replace("\"severity\": \"CRITICAL\", ", "")),
                "rule.severity")).isTrue();
    }

    // --- array / batch -------------------------------------------------------

    @Test
    void validArrayOfEventsAllPass() throws Exception {
        String json = "[" + VALID_JSON + "," + VALID_JSON + "]";
        List<Event> events = mapper.readValue(json, new TypeReference<List<Event>>() {});
        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(e -> assertThat(validator.validate(e)).isEmpty());
    }

    @Test
    void arrayWithOneInvalidEventFlagsOnlyThatElement() throws Exception {
        String invalid = VALID_JSON.replace("evt-00132", ""); // blank eventId
        String json = "[" + VALID_JSON + "," + invalid + "]";
        List<Event> events = mapper.readValue(json, new TypeReference<List<Event>>() {});

        assertThat(validator.validate(events.get(0))).isEmpty();
        assertThat(violatedField(validator.validate(events.get(1)), "eventId")).isTrue();
    }

    // --- invalid: enum + timestamp (Jackson) ---------------------------------

    @Test
    void invalidCategoryRejectedByJackson() {
        String json = VALID_JSON.replace("INJECTION", "NOT_A_CATEGORY");
        assertThatThrownBy(() -> mapper.readValue(json, Event.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void invalidActionRejectedByJackson() {
        String json = VALID_JSON.replace("\"action\": \"DENY\"", "\"action\": \"BLOCK\"");
        assertThatThrownBy(() -> mapper.readValue(json, Event.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void invalidTimestampFormatRejectedByJackson() {
        String json = VALID_JSON.replace("2026-05-20T14:32:10Z", "not-a-timestamp");
        assertThatThrownBy(() -> mapper.readValue(json, Event.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void canonicalTimestampParsesToCorrectInstant() throws Exception {
        Event event = mapper.readValue(VALID_JSON, Event.class);
        assertThat(event.timestamp()).isEqualTo(Instant.parse("2026-05-20T14:32:10Z"));
    }

    @Test
    void timestampWithoutOffsetRejected() {
        // "2026-05-20T14:32:10" has no Z/offset — ambiguous, so not a valid instant
        String json = VALID_JSON.replace("2026-05-20T14:32:10Z", "2026-05-20T14:32:10");
        assertThatThrownBy(() -> mapper.readValue(json, Event.class))
                .isInstanceOf(InvalidFormatException.class);
    }
}
