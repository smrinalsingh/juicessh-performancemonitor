package com.sonelli.juicessh.performancemonitor.parsers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class MemInfoParserTest {

    private static final List<String> WITH_AVAILABLE = Arrays.asList(
            "MemTotal:        4194304 kB",
            "MemFree:          524288 kB",
            "MemAvailable:    2097152 kB",
            "Buffers:          131072 kB",
            "Cached:          1048576 kB",
            "SwapTotal:       1048576 kB",
            "SwapFree:         786432 kB");

    @Test
    public void usesMemAvailableWhenPresent() {
        MetricReading reading = new MemInfoParser().parse(WITH_AVAILABLE);

        assertNotNull(reading);
        assertEquals(2048.0, reading.value, 0.01); // MB
        assertEquals("2.0 GB", reading.display);
    }

    @Test
    public void fallsBackToFreePlusBuffersPlusCached() {
        MetricReading reading = new MemInfoParser().parse(Arrays.asList(
                "MemTotal:        4194304 kB",
                "MemFree:          524288 kB",
                "Buffers:          131072 kB",
                "Cached:          1048576 kB"));

        assertNotNull(reading);
        // 524288 + 131072 + 1048576 = 1703936 kB = 1664 MB
        assertEquals(1664.0, reading.value, 0.01);
    }

    @Test
    public void buildsBreakdownRowsIncludingSwap() {
        MetricReading reading = new MemInfoParser().parse(WITH_AVAILABLE);

        assertNotNull(reading);
        List<String> labels = new java.util.ArrayList<>();
        for (DetailRow row : reading.detailRows) {
            labels.add(row.label);
        }
        assertEquals(Arrays.asList("Total", "Used", "Available", "Buffers", "Cached", "Swap"), labels);
        assertEquals("256 MB / 1.0 GB", reading.detailRows.get(5).value);
    }

    @Test
    public void omitsSwapRowWhenNoSwap() {
        MetricReading reading = new MemInfoParser().parse(Arrays.asList(
                "MemTotal:        4194304 kB",
                "MemAvailable:    2097152 kB",
                "SwapTotal:             0 kB",
                "SwapFree:              0 kB"));

        assertNotNull(reading);
        for (DetailRow row : reading.detailRows) {
            if (row.label.equals("Swap")) {
                throw new AssertionError("Swap row should be omitted when SwapTotal is 0");
            }
        }
    }

    @Test
    public void missingMemTotalYieldsNoReading() {
        assertNull(new MemInfoParser().parse(Collections.singletonList("MemFree: 100 kB")));
    }
}
