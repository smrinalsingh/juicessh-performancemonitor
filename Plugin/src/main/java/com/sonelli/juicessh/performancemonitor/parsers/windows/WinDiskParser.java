package com.sonelli.juicessh.performancemonitor.parsers.windows;

import com.sonelli.juicessh.performancemonitor.helpers.Format;
import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses {@code Win32_LogicalDisk} fixed drives, one {@code DeviceID|size|free}
 * line per drive (sizes in bytes). Headline is the system drive (C:) if present,
 * else the first drive; every drive is a detail row.
 */
public class WinDiskParser {

    public MetricReading parse(List<String> lines) {
        List<DetailRow> rows = new ArrayList<>();
        double headlinePct = Double.NaN;

        for (String line : lines) {
            String[] parts = line.split("\\|");
            if (parts.length < 3) {
                continue;
            }
            String id = parts[0].trim();
            long size;
            long free;
            try {
                size = Long.parseLong(parts[1].trim());
                free = Long.parseLong(parts[2].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (size <= 0) {
                continue;
            }
            long used = size - free;
            double pct = (double) used / size * 100.0;
            long sizeKb = size / 1024;
            long usedKb = used / 1024;

            // First drive seeds the headline; C: overrides it as the "root".
            if (Double.isNaN(headlinePct) || id.equalsIgnoreCase("C:")) {
                headlinePct = pct;
            }
            rows.add(new DetailRow(id,
                    String.format(Locale.US, "%.0f%%  (%s / %s)", pct,
                            Format.formatKb(usedKb), Format.formatKb(sizeKb)),
                    pct / 100.0));
        }

        if (rows.isEmpty() || Double.isNaN(headlinePct)) {
            return null;
        }
        return MetricReading.of(headlinePct, String.format(Locale.US, "%.0f%%", headlinePct), rows);
    }
}
