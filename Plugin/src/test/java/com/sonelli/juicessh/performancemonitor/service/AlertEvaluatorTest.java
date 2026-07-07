package com.sonelli.juicessh.performancemonitor.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.sonelli.juicessh.performancemonitor.model.AlertRule;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;
import com.sonelli.juicessh.performancemonitor.model.MetricSnapshot;
import com.sonelli.juicessh.performancemonitor.model.MetricType;
import com.sonelli.juicessh.performancemonitor.service.AlertEvaluator.AlertEvent;

import org.junit.Test;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;

public class AlertEvaluatorTest {

    private static final long SUSTAIN = 30_000;
    private static final long COOLDOWN = 300_000;

    private static final List<AlertRule> CPU_ABOVE_90 =
            Collections.singletonList(new AlertRule(MetricType.CPU, 90, false));
    private static final List<AlertRule> RAM_BELOW_256 =
            Collections.singletonList(new AlertRule(MetricType.RAM, 256, true));

    private static MetricSnapshot snapshot(MetricType type, double value, long ts) {
        EnumMap<MetricType, MetricReading> readings = new EnumMap<>(MetricType.class);
        readings.put(type, MetricReading.of(value, String.valueOf(value), Collections.emptyList()));
        return new MetricSnapshot(ts, "conn", readings);
    }

    @Test
    public void firesOnlyAfterSustainedBreach() {
        AlertEvaluator evaluator = new AlertEvaluator();

        assertTrue(evaluator.evaluate(snapshot(MetricType.CPU, 95, 0), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 0).isEmpty());
        assertTrue(evaluator.evaluate(snapshot(MetricType.CPU, 95, 15_000), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 15_000).isEmpty());

        List<AlertEvent> fired = evaluator.evaluate(snapshot(MetricType.CPU, 95, 30_000), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 30_000);
        assertEquals(1, fired.size());
        assertEquals(MetricType.CPU, fired.get(0).metric);
        assertEquals(95.0, fired.get(0).value, 0.001);
        assertEquals(90.0, fired.get(0).threshold, 0.001);
    }

    @Test
    public void spikeShorterThanSustainNeverFires() {
        AlertEvaluator evaluator = new AlertEvaluator();

        evaluator.evaluate(snapshot(MetricType.CPU, 95, 0), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 0);
        evaluator.evaluate(snapshot(MetricType.CPU, 50, 10_000), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 10_000); // recovers
        // Breach again: the sustain window must restart.
        assertTrue(evaluator.evaluate(snapshot(MetricType.CPU, 95, 20_000), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 20_000).isEmpty());
        assertTrue(evaluator.evaluate(snapshot(MetricType.CPU, 95, 40_000), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 40_000).isEmpty());
        assertEquals(1, evaluator.evaluate(snapshot(MetricType.CPU, 95, 50_000), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 50_000).size());
    }

    @Test
    public void continuedBreachRespectsCooldown() {
        AlertEvaluator evaluator = new AlertEvaluator();

        evaluator.evaluate(snapshot(MetricType.CPU, 95, 0), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 0);
        assertEquals(1, evaluator.evaluate(snapshot(MetricType.CPU, 95, 30_000), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 30_000).size());

        // Still breached, but cooldown hasn't elapsed.
        assertTrue(evaluator.evaluate(snapshot(MetricType.CPU, 95, 60_000), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 60_000).isEmpty());
        assertTrue(evaluator.evaluate(snapshot(MetricType.CPU, 95, 320_000), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 320_000).isEmpty());

        // Cooldown elapsed (30s + 300s = 330s) and still breached: fires again.
        assertEquals(1, evaluator.evaluate(snapshot(MetricType.CPU, 95, 330_000), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 330_000).size());
    }

    @Test
    public void cooldownSurvivesRecoveryToPreventFlapSpam() {
        AlertEvaluator evaluator = new AlertEvaluator();

        evaluator.evaluate(snapshot(MetricType.CPU, 95, 0), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 0);
        assertEquals(1, evaluator.evaluate(snapshot(MetricType.CPU, 95, 30_000), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 30_000).size());

        // Flap: recover, then breach long enough to satisfy sustain again —
        // but within the cooldown, so it must stay quiet.
        evaluator.evaluate(snapshot(MetricType.CPU, 50, 40_000), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 40_000);
        evaluator.evaluate(snapshot(MetricType.CPU, 95, 50_000), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 50_000);
        assertTrue(evaluator.evaluate(snapshot(MetricType.CPU, 95, 80_000), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 80_000).isEmpty());
    }

    @Test
    public void belowRuleFiresWhenValueDropsUnderThreshold() {
        AlertEvaluator evaluator = new AlertEvaluator();

        assertTrue(evaluator.evaluate(snapshot(MetricType.RAM, 512, 0), RAM_BELOW_256, 0, COOLDOWN, 0).isEmpty());
        assertEquals(1, evaluator.evaluate(snapshot(MetricType.RAM, 128, 1_000), RAM_BELOW_256, 0, COOLDOWN, 1_000).size());
    }

    @Test
    public void missingReadingNeitherBreachesNorRecovers() {
        AlertEvaluator evaluator = new AlertEvaluator();

        evaluator.evaluate(snapshot(MetricType.CPU, 95, 0), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 0);
        // A tick with no CPU reading (e.g. RAM-only snapshot) keeps the window open.
        evaluator.evaluate(snapshot(MetricType.RAM, 512, 15_000), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 15_000);
        assertEquals(1, evaluator.evaluate(snapshot(MetricType.CPU, 95, 30_000), CPU_ABOVE_90, SUSTAIN, COOLDOWN, 30_000).size());
    }

    @Test
    public void zeroSustainFiresImmediately() {
        AlertEvaluator evaluator = new AlertEvaluator();
        assertEquals(1, evaluator.evaluate(snapshot(MetricType.CPU, 95, 0), CPU_ABOVE_90, 0, COOLDOWN, 0).size());
    }
}
