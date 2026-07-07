package com.sonelli.juicessh.performancemonitor.data;

import android.content.Context;
import android.util.Log;

import com.sonelli.juicessh.performancemonitor.model.MetricReading;
import com.sonelli.juicessh.performancemonitor.model.MetricSnapshot;
import com.sonelli.juicessh.performancemonitor.model.MetricType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persists metric snapshots to Room off the main thread and enforces retention.
 *
 * <p>Writes are throttled to at most one row per metric per {@link
 * #WRITE_INTERVAL_MS}, independent of the (possibly 1s) poll interval, so a
 * long session doesn't balloon the database — at 10s cadence, 48h of six
 * metrics is roughly 100k rows. Old rows are pruned on start and hourly.
 */
public class HistoryRecorder {

    private static final String TAG = "HistoryRecorder";

    static final long WRITE_INTERVAL_MS = 10_000;
    static final long RETENTION_MS = 48L * 60 * 60 * 1000;   // 48 hours
    private static final long PRUNE_INTERVAL_MS = 60L * 60 * 1000; // hourly

    private final MetricSampleDao dao;
    private final ExecutorService executor;
    private final String connectionId;

    private final Map<MetricType, Long> lastWrite = new EnumMap<>(MetricType.class);
    private long lastPrune = 0;

    public HistoryRecorder(Context context, String connectionId) {
        this.dao = AppDatabase.getInstance(context).metricSampleDao();
        this.executor = Executors.newSingleThreadExecutor();
        this.connectionId = connectionId;
        prune(); // clear anything already past retention on connect
    }

    /** Records the numeric readings of a snapshot, subject to the write throttle. */
    public void record(MetricSnapshot snapshot) {
        if (connectionId == null || connectionId.isEmpty()) {
            return; // can't key rows without a connection id
        }
        final List<MetricSampleEntity> batch = new ArrayList<>();
        for (Map.Entry<MetricType, MetricReading> entry : snapshot.readings.entrySet()) {
            MetricReading reading = entry.getValue();
            if (!reading.hasValue()) {
                continue;
            }
            MetricType type = entry.getKey();
            Long last = lastWrite.get(type);
            if (last != null && snapshot.timestampMs - last < WRITE_INTERVAL_MS) {
                continue;
            }
            lastWrite.put(type, snapshot.timestampMs);
            batch.add(MetricSampleEntity.of(connectionId, type.id, snapshot.timestampMs, (float) reading.value));
        }
        if (!batch.isEmpty()) {
            executor.execute(() -> {
                try {
                    dao.insertAll(batch);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Failed to persist metric samples", e);
                }
            });
        }

        long now = snapshot.timestampMs;
        if (now - lastPrune >= PRUNE_INTERVAL_MS) {
            lastPrune = now;
            prune();
        }
    }

    private void prune() {
        executor.execute(() -> {
            try {
                dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to prune old metric samples", e);
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }
}
