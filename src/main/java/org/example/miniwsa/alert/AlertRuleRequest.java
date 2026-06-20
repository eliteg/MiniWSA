package org.example.miniwsa.alert;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.example.miniwsa.event.Category;

public record AlertRuleRequest(
        @NotNull Category category,
        @Min(1) int threshold,
        @Min(1) int windowMinutes) {}
