package org.example.miniwsa.event;

/**
 * Rule severity (assignment domain model) and its threat-score weight .
 * The weights are the assignment's fixed contract (CRITICAL=40, HIGH=30, MEDIUM=20, LOW=10),
 * so they live on the enum rather than in config.
 */
public enum Severity {
    CRITICAL(40),
    HIGH(30),
    MEDIUM(20),
    LOW(10);

    private final int weight;

    Severity(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
