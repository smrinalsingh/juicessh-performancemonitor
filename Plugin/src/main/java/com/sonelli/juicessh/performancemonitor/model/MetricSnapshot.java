package com.sonelli.juicessh.performancemonitor.model;

import java.util.EnumMap;

/**
 * All metric readings produced by one tick of the batched poll command, sharing
 * a single timestamp. Metrics that produced nothing this tick are absent.
 */
public class MetricSnapshot {

    public final long timestampMs;
    public final String connectionId;
    public final EnumMap<MetricType, MetricReading> readings;

    public MetricSnapshot(long timestampMs, String connectionId, EnumMap<MetricType, MetricReading> readings) {
        this.timestampMs = timestampMs;
        this.connectionId = connectionId;
        this.readings = readings != null ? readings : new EnumMap<>(MetricType.class);
    }

    public MetricReading get(MetricType type) {
        return readings.get(type);
    }
}
