package com.sonelli.juicessh.performancemonitor.parsers.windows;

import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses {@code Win32_PerfFormattedData_PerfOS_Processor} output, one
 * {@code name=percent} line per logical processor. {@code _Total} is the
 * aggregate headline; numeric names ("0", "1", …) are per-core detail rows.
 * The perf-formatted counter is already a percentage, so no delta is needed.
 */
public class WinCpuParser {

    public MetricReading parse(List<String> lines) {
        Double aggregate = null;
        List<DetailRow> rows = new ArrayList<>();
        double coreSum = 0;
        int coreCount = 0;

        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String name = line.substring(0, eq).trim();
            double pct;
            try {
                pct = Double.parseDouble(line.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (pct < 0) pct = 0;
            if (pct > 100) pct = 100;

            if (name.equalsIgnoreCase("_Total")) {
                aggregate = pct;
            } else {
                rows.add(new DetailRow("Core " + name, String.format(Locale.US, "%.0f%%", pct), pct / 100.0));
                coreSum += pct;
                coreCount++;
            }
        }

        if (aggregate == null) {
            if (coreCount == 0) {
                return null;
            }
            aggregate = coreSum / coreCount; // fall back to the average of the cores
        }
        return MetricReading.of(aggregate, String.format(Locale.US, "%.0f%%", aggregate), rows);
    }
}
