package com.sonelli.juicessh.performancemonitor.model;

/**
 * One enabled alert threshold. Thresholds use the metric's stored value
 * semantics: CPU/disk %, RAM available MB, temperature °C, 1-minute load.
 * RAM alerts when the value drops <em>below</em> the threshold; every other
 * metric alerts when it rises above.
 */
public class AlertRule {

    public final MetricType metric;
    public final double threshold;
    public final boolean fireWhenBelow;

    public AlertRule(MetricType metric, double threshold, boolean fireWhenBelow) {
        this.metric = metric;
        this.threshold = threshold;
        this.fireWhenBelow = fireWhenBelow;
    }
}
