package com.sonelli.juicessh.performancemonitor.parsers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class CpuStatParserTest {

    // user nice system idle iowait irq softirq steal guest guest_nice
    private static final List<String> TICK_1 = Arrays.asList(
            "cpu  1000 0 500 8000 500 0 0 0 0 0",
            "cpu0 500 0 250 4000 250 0 0 0 0 0",
            "cpu1 500 0 250 4000 250 0 0 0 0 0",
            "intr 12345",
            "ctxt 999");

    // Aggregate deltas: total=+1000, idle(idle+iowait)=+500 -> 50% busy.
    // cpu0: total=+500, idle=+100 -> 80% busy. cpu1: total=+500, idle=+400 -> 20%.
    private static final List<String> TICK_2 = Arrays.asList(
            "cpu  1400 0 600 8400 600 0 0 0 0 0",
            "cpu0 800 0 350 4080 270 0 0 0 0 0",
            "cpu1 600 0 250 4320 330 0 0 0 0 0");

    @Test
    public void firstTickYieldsNoReading() {
        assertNull(new CpuStatParser().parse(TICK_1));
    }

    @Test
    public void secondTickComputesDeltas() {
        CpuStatParser parser = new CpuStatParser();
        parser.parse(TICK_1);
        MetricReading reading = parser.parse(TICK_2);

        assertNotNull(reading);
        assertEquals(50.0, reading.value, 0.01);
        assertEquals("50%", reading.display);

        assertEquals(2, reading.detailRows.size());
        assertEquals("Core 0", reading.detailRows.get(0).label);
        assertEquals("80%", reading.detailRows.get(0).value);
        assertEquals("Core 1", reading.detailRows.get(1).label);
        assertEquals("20%", reading.detailRows.get(1).value);
    }

    @Test
    public void guestTimeIsNotDoubleCounted() {
        CpuStatParser parser = new CpuStatParser();
        // guest (9th value) already included in user; totals must ignore it.
        parser.parse(Arrays.asList("cpu  1000 0 500 8000 500 0 0 0 700 0"));
        MetricReading reading = parser.parse(Arrays.asList("cpu  1400 0 600 8400 600 0 0 0 900 0"));

        assertNotNull(reading);
        assertEquals(50.0, reading.value, 0.01);
    }

    @Test
    public void unchangedCountersYieldNoReading() {
        CpuStatParser parser = new CpuStatParser();
        parser.parse(TICK_1);
        assertNull(parser.parse(TICK_1)); // totalDelta == 0
    }
}
