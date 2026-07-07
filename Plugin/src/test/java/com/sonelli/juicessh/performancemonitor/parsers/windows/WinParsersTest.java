package com.sonelli.juicessh.performancemonitor.parsers.windows;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class WinParsersTest {

    // ---- CPU ----------------------------------------------------------------

    @Test
    public void cpuUsesTotalAndListsCores() {
        MetricReading r = new WinCpuParser().parse(Arrays.asList("0=10", "1=30", "_Total=20"));
        assertNotNull(r);
        assertEquals(20.0, r.value, 0.01);
        assertEquals("20%", r.display);
        assertEquals(2, r.detailRows.size());
        assertEquals("Core 0", r.detailRows.get(0).label);
    }

    @Test
    public void cpuFallsBackToCoreAverageWithoutTotal() {
        MetricReading r = new WinCpuParser().parse(Arrays.asList("0=10", "1=30"));
        assertNotNull(r);
        assertEquals(20.0, r.value, 0.01);
    }

    @Test
    public void cpuEmptyYieldsNull() {
        assertNull(new WinCpuParser().parse(Collections.<String>emptyList()));
    }

    // ---- Memory -------------------------------------------------------------

    @Test
    public void memComputesAvailableAndUsed() {
        // free 2 GB, total 8 GB (values in KB)
        MetricReading r = new WinMemParser().parse(Arrays.asList("free=2097152", "total=8388608"));
        assertNotNull(r);
        assertEquals(2048.0, r.value, 0.01); // MB available
        assertEquals("2.0 GB", r.display);
        assertEquals("Used", r.detailRows.get(1).label);
        assertEquals(0.75, r.detailRows.get(1).fraction, 0.001); // 6/8
    }

    @Test
    public void memMissingTotalYieldsNull() {
        assertNull(new WinMemParser().parse(Collections.singletonList("free=100")));
    }

    // ---- Disk ---------------------------------------------------------------

    @Test
    public void diskHeadlinesSystemDrive() {
        // D: 50% used, C: 25% used — C: must headline even though it's listed second.
        MetricReading r = new WinDiskParser().parse(Arrays.asList(
                "D:|1000000000|500000000",
                "C:|1000000000|750000000"));
        assertNotNull(r);
        assertEquals(25.0, r.value, 0.5);
        assertEquals(2, r.detailRows.size());
    }

    @Test
    public void diskMalformedYieldsNull() {
        assertNull(new WinDiskParser().parse(Collections.singletonList("garbage")));
    }

    // ---- Network ------------------------------------------------------------

    @Test
    public void netSumsInterfacesExcludingLoopback() {
        MetricReading r = new WinNetParser().parse(Arrays.asList(
                "Loopback Pseudo-Interface 1|999999|999999",
                "Ethernet|1048576|0"), false);
        assertNotNull(r);
        assertEquals(1048576.0, r.value, 1.0);
        assertEquals("1.0 MB/s", r.display);
        assertEquals(1, r.detailRows.size());
        assertEquals("Ethernet", r.detailRows.get(0).label);
    }

    @Test
    public void netEmptyYieldsNull() {
        assertNull(new WinNetParser().parse(Collections.<String>emptyList(), false));
    }

    // ---- Temperature --------------------------------------------------------

    @Test
    public void tempConvertsDeciKelvinToCelsius() {
        // 3132 deci-Kelvin = 313.2 K = 40.05 °C
        MetricReading r = new WinTempParser().parse(Collections.singletonList("3132"), false);
        assertNotNull(r);
        assertEquals(40.05, r.value, 0.1);
        assertEquals("40°C", r.display);
    }

    @Test
    public void tempEmptyYieldsNull() {
        assertNull(new WinTempParser().parse(Collections.<String>emptyList(), false));
    }
}
