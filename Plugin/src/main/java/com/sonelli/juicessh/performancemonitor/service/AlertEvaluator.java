package com.sonelli.juicessh.performancemonitor.service;

import com.sonelli.juicessh.performancemonitor.model.AlertRule;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;
import com.sonelli.juicessh.performancemonitor.model.MetricSnapshot;
import com.sonelli.juicessh.performancemonitor.model.MetricType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Stateful threshold checker, one instance per monitored session. A rule fires
 * only after its threshold has been breached continuously for the sustain
 * window (so a single spike can't alert), and re-fires no more often than the
 * cooldown (so a flapping metric can't spam — the cooldown survives recovery).
 * Pure Java with the clock passed in, so the state machine is unit-testable.
 */
public class AlertEvaluator {

    /** A rule that fired: carries what's needed to render a notification. */
    public static class AlertEvent {
        public final MetricType metric;
        public final double value;
        public final double threshold;
        public final boolean fireWhenBelow;

        AlertEvent(MetricType metric, double value, double threshold, boolean fireWhenBelow) {
            this.metric = metric;
            this.value = value;
            this.threshold = threshold;
            this.fireWhenBelow = fireWhenBelow;
        }
    }

    private static class State {
        long breachStartMs = -1;
        long lastFiredMs = Long.MIN_VALUE / 2; // "never", without overflow risk
    }

    private final Map<MetricType, State> states = new EnumMap<>(MetricType.class);

    /**
     * Feeds one snapshot through the given rules. A metric with no numeric
     * reading this tick keeps its state (gaps neither breach nor recover).
     */
    public List<AlertEvent> evaluate(MetricSnapshot snapshot, List<AlertRule> rules,
                                     long sustainMs, long cooldownMs, long nowMs) {
        List<AlertEvent> events = new ArrayList<>();
        for (AlertRule rule : rules) {
            MetricReading reading = snapshot.get(rule.metric);
            if (reading == null || !reading.hasValue()) {
                continue;
            }
            State state = states.get(rule.metric);
            if (state == null) {
                state = new State();
                states.put(rule.metric, state);
            }

            boolean breached = rule.fireWhenBelow
                    ? reading.value < rule.threshold
                    : reading.value > rule.threshold;

            if (!breached) {
                state.breachStartMs = -1; // recovered
                continue;
            }
            if (state.breachStartMs < 0) {
                state.breachStartMs = nowMs;
            }
            if (nowMs - state.breachStartMs >= sustainMs && nowMs - state.lastFiredMs >= cooldownMs) {
                state.lastFiredMs = nowMs;
                events.add(new AlertEvent(rule.metric, reading.value, rule.threshold, rule.fireWhenBelow));
            }
        }
        return events;
    }
}
