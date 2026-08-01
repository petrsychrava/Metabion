package com.metabion.domain;

public enum RedFlagSeverity {
    ROUTINE_REVIEW(1), URGENT_REVIEW(2), EMERGENCY(3);

    private final int priority;

    RedFlagSeverity(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
