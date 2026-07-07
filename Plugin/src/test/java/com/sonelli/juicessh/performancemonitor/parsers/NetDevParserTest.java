package com.sonelli.juicessh.performancemonitor.parsers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class NetDevParserTest {

    private static final String HEADER_1 = "Inter-|   Receive                                                |  Transmit";
    private static final String HEADER_2 = " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed";

    private static List<String> tick(long eth0Rx, long eth0Tx, long loRx) {
        return Arrays.asList(
                HEADER_1,
                HEADER_2,
                String.format("    lo: %d 100 0 0 0 0 0 0 %d 100 0 0 0 0 0 0", loRx, loRx),
                String.format("  eth0: %d 2000 0 0 0 0 0 0 %d 1500 0 0 0 0 0 0", eth0Rx, eth0Tx));
    }

    @Test
    public void firstTickYieldsNoReading() {
        assertNull(new NetDevParser().parse(tick(1000, 1000, 500), 10_000, false));
    }

    @Test
    public void computesRatePerSecondExcludingLoopback() {
        NetDevParser parser = new NetDevParser();
        parser.parse(tick(1_000_000, 1_000_000, 999_999_999), 10_000, false);
        // +2 MiB combined over 2 seconds = 1 MiB/s; loopback delta must not count.
        MetricReading reading = parser.parse(tick(2_048_576, 2_048_576, 5_999_999_999L), 12_000, false);

        assertNotNull(reading);
        assertEquals(1_048_576.0, reading.value, 1.0);
        assertEquals("1.0 MB/s", reading.display);
        assertEquals(1, reading.detailRows.size());
        assertEquals("eth0", reading.detailRows.get(0).label);
    }

    @Test
    public void formatsBitsWhenRequested() {
        NetDevParser parser = new NetDevParser();
        parser.parse(tick(0, 0, 0), 10_000, true);
        // +250_000 bytes over 2s = 125_000 B/s = 1 Mbps
        MetricReading reading = parser.parse(tick(125_000, 125_000, 0), 12_000, true);

        assertNotNull(reading);
        assertEquals("1.0 Mbps", reading.display);
    }

    @Test
    public void counterResetClampsToZeroInsteadOfNegative() {
        NetDevParser parser = new NetDevParser();
        parser.parse(tick(5_000_000, 5_000_000, 0), 10_000, false);
        MetricReading reading = parser.parse(tick(100, 100, 0), 12_000, false);

        assertNotNull(reading);
        assertEquals(0.0, reading.value, 0.001);
    }

    @Test
    public void ticksCloserThanHalfASecondYieldNoReading() {
        NetDevParser parser = new NetDevParser();
        parser.parse(tick(1000, 1000, 0), 10_000, false);
        assertNull(parser.parse(tick(2000, 2000, 0), 10_300, false));
    }
}
