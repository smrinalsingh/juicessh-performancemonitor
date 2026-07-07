package com.sonelli.juicessh.performancemonitor.model;

/**
 * The metrics the app monitors. Each has a stable integer id that is persisted
 * (Room rows, notification ids), so ids must never be reused or renumbered.
 */
public enum MetricType {

    CPU(0),
    RAM(1),
    TEMPERATURE(2),
    LOAD(3),
    NETWORK(4),
    DISK(5);

    public final int id;

    MetricType(int id) {
        this.id = id;
    }

    public static MetricType fromId(int id) {
        for (MetricType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return null;
    }
}
