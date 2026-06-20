package org.example.miniwsa.samples;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/v1/events", "/v2/events"})
class SamplesController {

    private static final int MAX_LIMIT = 100;

    private final SamplesRepository repository;

    SamplesController(SamplesRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/samples")
    public SamplesResponse samples(
            @RequestParam(required = false) Long configId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        Instant effectiveFrom = from != null ? from : Instant.now().minus(24, ChronoUnit.HOURS);
        return repository.query(configId, effectiveFrom, to, category, action,
                Math.min(limit, MAX_LIMIT), offset);
    }
}
