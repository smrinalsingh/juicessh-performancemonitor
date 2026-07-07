package com.sonelli.juicessh.performancemonitor.parsers;

import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses {@code /proc/loadavg} (more portable than parsing {@code uptime}) into
 * the 1-minute load average (headline) with 1/5/15-minute averages and
 * running/total process counts as detail rows.
 */
public class LoadAvgParser {

    /** @return the reading, or null if no parseable loadavg line was found. */
    public MetricReading parse(List<String> lines) {
        for (String line : lines) {
            String[] t = line.trim().split("\\s+");
            if (t.length < 3) {
                continue;
            }
            try {
                double load1 = Double.parseDouble(t[0]);

                List<DetailRow> rows = new ArrayList<>();
                rows.add(new DetailRow("1 min", t[0]));
                rows.add(new DetailRow("5 min", t[1]));
                rows.add(new DetailRow("15 min", t[2]));
                if (t.length >= 4 && t[3].contains("/")) {
                    rows.add(new DetailRow("Processes (running/total)", t[3]));
                }

                return MetricReading.of(load1, String.format(Locale.US, "%.2f", load1), rows);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
