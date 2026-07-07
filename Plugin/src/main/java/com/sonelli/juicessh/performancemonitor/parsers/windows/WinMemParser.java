package com.sonelli.juicessh.performancemonitor.parsers.windows;

import com.sonelli.juicessh.performancemonitor.helpers.Format;
import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code Win32_OperatingSystem} free/total physical memory, emitted as
 * {@code free=<kB>} and {@code total=<kB>} lines (both already in KB, matching
 * the Linux meminfo value semantics). Headline is available RAM.
 */
public class WinMemParser {

    public MetricReading parse(List<String> lines) {
        long free = -1;
        long total = -1;
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            long value;
            try {
                value = Long.parseLong(line.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (key.equalsIgnoreCase("free")) {
                free = value;
            } else if (key.equalsIgnoreCase("total")) {
                total = value;
            }
        }

        if (total <= 0 || free < 0) {
            return null;
        }
        long used = total - free;

        List<DetailRow> rows = new ArrayList<>();
        rows.add(new DetailRow("Total", Format.formatKb(total)));
        rows.add(new DetailRow("Used", Format.formatKb(used), (double) used / total));
        rows.add(new DetailRow("Available", Format.formatKb(free), (double) free / total));

        return MetricReading.of(free / 1024.0, Format.formatKb(free), rows);
    }
}
