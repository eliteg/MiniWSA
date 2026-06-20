package org.example.miniwsa.stats;

import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/v1/events", "/v2/events"})
public class StatsController {

    private static final Instant EPOCH = Instant.EPOCH;

    private final StatsRepository repository;

    public StatsController(StatsRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/stats")
    public StatsResponse stats(
            @RequestParam(required = false) Long configId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return repository.query(
                configId,
                from != null ? from : EPOCH,
                to != null ? to : Instant.now());
    }
}
