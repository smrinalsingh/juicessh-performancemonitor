package com.sonelli.juicessh.performancemonitor.parsers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DfParserTest {

    private static final List<String> TYPICAL = Arrays.asList(
            "Filesystem     1024-blocks     Used Available Capacity Mounted on",
            "/dev/sda1         51474912 20589964  28247128      43% /",
            "tmpfs              4194304        0   4194304       0% /dev/shm",
            "devtmpfs           4186112        0   4186112       0% /dev",
            "/dev/sdb1        103081248 82464998  15461250      85% /data");

    @Test
    public void headlinesRootFilesystemAndSkipsPseudoFs() {
        MetricReading reading = new DfParser().parse(TYPICAL);

        assertNotNull(reading);
        assertEquals(43.0, reading.value, 0.01);
        assertEquals("43%", reading.display);

        assertEquals(2, reading.detailRows.size());
        assertEquals("/", reading.detailRows.get(0).label);
        assertTrue(reading.detailRows.get(0).value.startsWith("43%"));
        assertEquals("/data", reading.detailRows.get(1).label);
        assertEquals(0.85, reading.detailRows.get(1).fraction, 0.001);
    }

    @Test
    public void noRootMountYieldsRowsOnly() {
        MetricReading reading = new DfParser().parse(Arrays.asList(
                "Filesystem 1024-blocks Used Available Capacity Mounted on",
                "/dev/sdb1 1000 500 500 50% /data"));

        assertNotNull(reading);
        assertFalse(reading.hasValue());
        assertNull(reading.display);
        assertEquals(1, reading.detailRows.size());
    }

    @Test
    public void headerOnlyYieldsNoReading() {
        assertNull(new DfParser().parse(Collections.singletonList(
                "Filesystem 1024-blocks Used Available Capacity Mounted on")));
    }
}
