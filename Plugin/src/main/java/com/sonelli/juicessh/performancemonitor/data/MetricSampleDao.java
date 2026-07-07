package com.sonelli.juicessh.performancemonitor.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

/**
 * Room access for persisted metric samples. Chart queries bucket samples with
 * integer division on the timestamp so a range of any length returns a bounded
 * number of points; the index on {@code (connectionId, metric, timestamp)}
 * keeps both the GROUP-BY and the pruning delete cheap.
 */
@Dao
public interface MetricSampleDao {

    @Insert
    void insertAll(List<MetricSampleEntity> samples);

    /**
     * Aggregates one metric's samples for a connection into fixed-size time
     * buckets. Returns avg/min/max per bucket, oldest first.
     */
    @Query("SELECT (timestamp / :bucketMs) * :bucketMs AS bucketTs, "
            + "AVG(value) AS avgValue, MIN(value) AS minValue, MAX(value) AS maxValue "
            + "FROM metric_samples "
            + "WHERE connectionId = :connectionId AND metric = :metric AND timestamp >= :fromTs "
            + "GROUP BY timestamp / :bucketMs ORDER BY bucketTs")
    List<ChartPoint> getBucketed(String connectionId, int metric, long fromTs, long bucketMs);

    /** Raw samples for a connection since a cut-off, oldest first (for CSV export). */
    @Query("SELECT * FROM metric_samples "
            + "WHERE connectionId = :connectionId AND timestamp >= :fromTs "
            + "ORDER BY timestamp")
    List<MetricSampleEntity> getRawSince(String connectionId, long fromTs);

    @Query("SELECT COUNT(*) FROM metric_samples WHERE connectionId = :connectionId")
    int countForConnection(String connectionId);

    @Query("DELETE FROM metric_samples WHERE timestamp < :cutoff")
    int deleteOlderThan(long cutoff);
}
