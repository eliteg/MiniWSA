package org.example.miniwsa.event;

/**
 * Action taken on the request (assignment domain model) and its threat-score add-on
 * DENY +20, ALERT +10, MONITOR +0 — the assignment's fixed contract.
 */
public enum Action {
    DENY(20),
    ALERT(10),
    MONITOR(0);

    private final int points;

    Action(int points) {
        this.points = points;
    }

    public int points() {
        return points;
    }
}
