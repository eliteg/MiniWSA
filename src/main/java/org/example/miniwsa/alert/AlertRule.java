package org.example.miniwsa.alert;

import java.time.Instant;

public record AlertRule(long id, String category, int threshold, int windowMinutes, Instant createdAt) {}
