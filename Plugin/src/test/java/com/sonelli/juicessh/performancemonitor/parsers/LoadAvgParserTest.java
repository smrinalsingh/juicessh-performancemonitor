package com.sonelli.juicessh.performancemonitor.parsers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class LoadAvgParserTest {

    @Test
    public void parsesStandardLoadavgLine() {
        MetricReading reading = new LoadAvgParser().parse(
                Collections.singletonList("0.52 0.58 0.59 1/467 12345"));

        assertNotNull(reading);
        assertEquals(0.52, reading.value, 0.001);
        assertEquals("0.52", reading.display);

        assertEquals(4, reading.detailRows.size());
        assertEquals("1 min", reading.detailRows.get(0).label);
        assertEquals("0.52", reading.detailRows.get(0).value);
        assertEquals("5 min", reading.detailRows.get(1).label);
        assertEquals("15 min", reading.detailRows.get(2).label);
        assertEquals("Processes (running/total)", reading.detailRows.get(3).label);
        assertEquals("1/467", reading.detailRows.get(3).value);
    }

    @Test
    public void omitsProcessRowWithoutSlashField() {
        MetricReading reading = new LoadAvgParser().parse(
                Collections.singletonList("0.10 0.20 0.30"));

        assertNotNull(reading);
        assertEquals(3, reading.detailRows.size());
    }

    @Test
    public void unparseableInputYieldsNoReading() {
        assertNull(new LoadAvgParser().parse(Arrays.asList("garbage line", "an other")));
        assertNull(new LoadAvgParser().parse(Collections.<String>emptyList()));
    }
}
