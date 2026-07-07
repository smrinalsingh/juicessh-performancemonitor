package com.sonelli.juicessh.performancemonitor.data;

/**
 * One time bucket of a metric's history, produced by the DAO's GROUP-BY query.
 * Bucketing keeps chart point counts bounded regardless of the time range.
 */
public class ChartPoint {

    /** Bucket start, wall-clock ms (timestamp floored to the bucket size). */
    public long bucketTs;

    public float avgValue;
    public float minValue;
    public float maxValue;
}
