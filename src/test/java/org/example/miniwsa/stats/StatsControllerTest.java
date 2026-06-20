package org.example.miniwsa.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.example.miniwsa.storage.EventStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StatsControllerTest {

    @MockBean EventStore eventStore;
    @MockBean StatsRepository statsRepository;

    @Autowired private TestRestTemplate template;

    private static final StatsResponse EMPTY = new StatsResponse(
            0, Map.of(), Map.of(), List.of(), List.of());

    private static final StatsResponse WITH_DATA = new StatsResponse(
            42,
            Map.of("INJECTION", new StatsResponse.CategoryStat(42, new BigDecimal("72.3"))),
            Map.of("DENY", 42L),
            List.of(new StatsResponse.AttackerStat("1.2.3.4", 42, new BigDecimal("72.3"))),
            List.of(new StatsResponse.PathStat("/login", 42)));

    @Test
    void withConfigIdPassesItToRepository() {
        when(statsRepository.query(eq(14227L), any(), any())).thenReturn(WITH_DATA);

        ResponseEntity<StatsResponse> r = template.getForEntity(
                "/v1/events/stats?configId=14227", StatsResponse.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().totalEvents()).isEqualTo(42);
        verify(statsRepository).query(eq(14227L), any(Instant.class), any(Instant.class));
    }

    @Test
    void withoutConfigIdPassesNullToRepository() {
        when(statsRepository.query(isNull(), any(), any())).thenReturn(EMPTY);

        template.getForEntity("/v1/events/stats", StatsResponse.class);

        verify(statsRepository).query(isNull(), any(Instant.class), any(Instant.class));
    }

    @Test
    void fromAndToAreForwardedToRepository() {
        when(statsRepository.query(any(), any(), any())).thenReturn(EMPTY);

        template.getForEntity(
                "/v1/events/stats?from=2026-01-01T00:00:00Z&to=2026-06-01T00:00:00Z",
                StatsResponse.class);

        verify(statsRepository).query(
                isNull(),
                eq(Instant.parse("2026-01-01T00:00:00Z")),
                eq(Instant.parse("2026-06-01T00:00:00Z")));
    }
}
