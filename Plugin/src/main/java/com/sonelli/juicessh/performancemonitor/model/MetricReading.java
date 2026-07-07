package com.sonelli.juicessh.performancemonitor.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One parsed reading of a single metric: a numeric value for the sparkline and
 * history, a formatted headline string, and the rows for the detail sheet.
 *
 * <p>Not every tick yields all three: delta-based metrics (CPU, network) produce
 * nothing on their first tick, and {@code df} can list mounts without a root
 * filesystem to headline. {@link #unavailable()} marks a data source that is
 * missing on the server entirely (its section of the batched command produced
 * no output).
 */
public class MetricReading {

    /** Numeric sample, or NaN when this reading carries no headline number. */
    public final double value;

    /** Formatted headline, or null when the headline should not change. */
    public final String display;

    /** Detail sheet rows; never null. */
    public final List<DetailRow> detailRows;

    /** True when the metric's data source does not exist on the server. */
    public final boolean unavailable;

    /** Why the metric is unavailable (e.g. "Not available on Windows"), or null. */
    public final String reason;

    private MetricReading(double value, String display, List<DetailRow> detailRows, boolean unavailable, String reason) {
        this.value = value;
        this.display = display;
        this.detailRows = detailRows != null ? detailRows : new ArrayList<>();
        this.unavailable = unavailable;
        this.reason = reason;
    }

    public static MetricReading of(double value, String display, List<DetailRow> detailRows) {
        return new MetricReading(value, display, detailRows, false, null);
    }

    /** A reading that only refreshes the detail sheet (e.g. df with no "/" mount). */
    public static MetricReading rowsOnly(List<DetailRow> detailRows) {
        return new MetricReading(Double.NaN, null, detailRows, false, null);
    }

    public static MetricReading unavailable() {
        return new MetricReading(Double.NaN, null, Collections.<DetailRow>emptyList(), true, null);
    }

    /** Unavailable with an explanation shown in the detail sheet (e.g. Windows has no load average). */
    public static MetricReading unavailable(String reason) {
        return new MetricReading(Double.NaN, null,
                Collections.singletonList(new DetailRow(reason, "")), true, reason);
    }

    /** Whether this reading carries a numeric sample for sparklines/history. */
    public boolean hasValue() {
        return !Double.isNaN(value);
    }
}
