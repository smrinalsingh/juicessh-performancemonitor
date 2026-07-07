package com.sonelli.juicessh.performancemonitor.parsers.windows;

import com.sonelli.juicessh.performancemonitor.helpers.Format;
import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code Win32_PerfFormattedData_Tcpip_NetworkInterface} throughput, one
 * {@code name|bytesReceivedPerSec|bytesSentPerSec} line per interface. The perf
 * counter is already a per-second rate, so (unlike Linux) there is no delta and
 * data is available on the first tick. Loopback/tunnel pseudo-interfaces are
 * excluded. Headline is total rx+tx across real interfaces.
 */
public class WinNetParser {

    public MetricReading parse(List<String> lines, boolean bits) {
        double totalRate = 0;
        List<DetailRow> rows = new ArrayList<>();
        boolean sawInterface = false;

        for (String line : lines) {
            String[] parts = line.split("\\|");
            if (parts.length < 3) {
                continue;
            }
            String name = parts[0].trim();
            if (name.isEmpty() || isPseudoInterface(name)) {
                continue;
            }
            double rate;
            try {
                rate = Double.parseDouble(parts[1].trim()) + Double.parseDouble(parts[2].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            sawInterface = true;
            totalRate += rate;
            rows.add(new DetailRow(name, Format.formatRate(rate, bits)));
        }

        if (!sawInterface) {
            return null;
        }
        return MetricReading.of(totalRate, Format.formatRate(totalRate, bits), rows);
    }

    private static boolean isPseudoInterface(String name) {
        String n = name.toLowerCase();
        return n.contains("loopback") || n.contains("isatap") || n.contains("teredo");
    }
}
