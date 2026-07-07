package com.sonelli.juicessh.performancemonitor.parsers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ThermalParserTest {

    @Test
    public void convertsMillidegreesAndPicksHottestZone() {
        MetricReading reading = new ThermalParser().parse(Arrays.asList(
                "x86_pkg_temp=45000",
                "acpitz=38500"), false);

        assertNotNull(reading);
        assertEquals(45.0, reading.value, 0.01);
        assertEquals("45°C", reading.display);
        assertEquals(2, reading.detailRows.size());
        assertEquals("x86_pkg_temp", reading.detailRows.get(0).label);
        assertEquals("45°C", reading.detailRows.get(0).value);
        assertEquals("39°C", reading.detailRows.get(1).value);
    }

    @Test
    public void parsesRaspberryPiVcgencmdFormat() {
        // vcgencmd prints e.g. temp=48.3'C — already in °C, and renamed to "CPU".
        MetricReading reading = new ThermalParser().parse(
                Collections.singletonList("temp=48.3'C"), false);

        assertNotNull(reading);
        assertEquals(48.3, reading.value, 0.01);
        assertEquals("CPU", reading.detailRows.get(0).label);
    }

    @Test
    public void fahrenheitAffectsDisplayButNotValue() {
        MetricReading reading = new ThermalParser().parse(
                Collections.singletonList("zone=50000"), true);

        assertNotNull(reading);
        assertEquals(50.0, reading.value, 0.01); // stored value stays °C
        assertEquals("122°F", reading.display);
    }

    @Test
    public void noParseableSensorsYieldsNoReading() {
        // A glob that matched nothing echoes a bare "=" line.
        assertNull(new ThermalParser().parse(Collections.singletonList("="), false));
        assertNull(new ThermalParser().parse(Collections.<String>emptyList(), false));
    }
}
