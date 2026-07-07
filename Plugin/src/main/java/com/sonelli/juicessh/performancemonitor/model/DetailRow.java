package com.sonelli.juicessh.performancemonitor.model;

/**
 * One line in a metric's tap-to-expand detail sheet, e.g. a single CPU core,
 * a mounted filesystem, or a network interface. {@link #fraction} (0..1) drives
 * an optional progress bar; use {@link Double#NaN} when there is nothing to plot.
 */
public class DetailRow {

    public final String label;
    public final String value;
    public final double fraction;

    public DetailRow(String label, String value) {
        this(label, value, Double.NaN);
    }

    public DetailRow(String label, String value, double fraction) {
        this.label = label;
        this.value = value;
        this.fraction = fraction;
    }

    public boolean hasBar() {
        return !Double.isNaN(fraction);
    }
}
