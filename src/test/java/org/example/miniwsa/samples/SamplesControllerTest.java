package org.example.miniwsa.samples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

import java.time.Instant;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.example.miniwsa.stats.StatsRepository;
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
class SamplesControllerTest {

    @MockBean EventStore eventStore;
    @MockBean StatsRepository statsRepository;
    @MockBean SamplesRepository samplesRepository;

    @Autowired private TestRestTemplate template;

    private static final SamplesResponse EMPTY = new SamplesResponse(0, 20, 0, List.of());

    @Test
    void defaultsLimitAndOffset() {
        when(samplesRepository.query(isNull(), any(Instant.class), isNull(), isNull(), isNull(), eq(20), eq(0)))
                .thenReturn(EMPTY);

        ResponseEntity<SamplesResponse> r = template.getForEntity(
                "/v1/events/samples", SamplesResponse.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(samplesRepository).query(isNull(), any(Instant.class), isNull(), isNull(), isNull(), eq(20), eq(0));
    }

    @Test
    void forwardsAllFilters() {
        when(samplesRepository.query(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new SamplesResponse(100, 10, 5, List.of()));

        ResponseEntity<SamplesResponse> r = template.getForEntity(
                "/v1/events/samples?configId=14227&limit=10&offset=5"
                + "&category=INJECTION&action=DENY"
                + "&from=2026-01-01T00:00:00Z&to=2026-06-01T00:00:00Z",
                SamplesResponse.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().total()).isEqualTo(100);
    }

    @Test
    void limitCappedAt100() {
        when(samplesRepository.query(isNull(), any(Instant.class), isNull(), isNull(), isNull(), eq(100), eq(0)))
                .thenReturn(EMPTY);

        template.getForEntity("/v1/events/samples?limit=999", SamplesResponse.class);

        verify(samplesRepository).query(isNull(), any(Instant.class), isNull(), isNull(), isNull(), eq(100), eq(0));
    }
}
