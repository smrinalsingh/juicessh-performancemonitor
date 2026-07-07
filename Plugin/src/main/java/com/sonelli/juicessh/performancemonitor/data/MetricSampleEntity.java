package com.sonelli.juicessh.performancemonitor.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One persisted metric sample. {@code metric} is {@link
 * com.sonelli.juicessh.performancemonitor.model.MetricType#id} (stable ints);
 * {@code value} uses the metric's stored semantics (CPU/disk %, RAM available
 * MB, hottest °C, 1-min load, network bytes/s).
 */
@Entity(tableName = "metric_samples",
        indices = {@Index({"connectionId", "metric", "timestamp"})})
public class MetricSampleEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** JuiceSSH connection UUID this sample belongs to. */
    @NonNull
    public String connectionId = "";

    public int metric;

    /** Wall-clock ms of the poll tick. */
    public long timestamp;

    public float value;

    public MetricSampleEntity() {
    }

    public static MetricSampleEntity of(@NonNull String connectionId, int metric, long timestamp, float value) {
        MetricSampleEntity entity = new MetricSampleEntity();
        entity.connectionId = connectionId;
        entity.metric = metric;
        entity.timestamp = timestamp;
        entity.value = value;
        return entity;
    }
}
