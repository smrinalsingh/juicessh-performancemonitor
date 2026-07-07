package com.sonelli.juicessh.performancemonitor.parsers;

import com.sonelli.juicessh.performancemonitor.helpers.Format;
import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code /proc/meminfo} into available RAM (headline, in MB) with a
 * total/used/buffers/cached/swap breakdown. Uses {@code MemAvailable} where the
 * kernel provides it, falling back to free+buffers+cached.
 */
public class MemInfoParser {

    private static final Pattern LINE = Pattern.compile("^(\\w+):\\s*([0-9]+)");

    /** @return the reading (value = available MB), or null if MemTotal is missing. */
    public MetricReading parse(List<String> lines) {
        final Map<String, Long> mem = new HashMap<>();

        for (String line : lines) {
            Matcher m = LINE.matcher(line);
            if (m.find()) {
                try {
                    mem.put(m.group(1), Long.parseLong(m.group(2)));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        long total = get(mem, "MemTotal");
        long free = get(mem, "MemFree");
        long buffers = get(mem, "Buffers");
        long cached = get(mem, "Cached");
        long available = mem.containsKey("MemAvailable")
                ? get(mem, "MemAvailable")
                : free + buffers + cached;
        long used = total - available;
        long swapTotal = get(mem, "SwapTotal");
        long swapFree = get(mem, "SwapFree");

        if (total <= 0) {
            return null;
        }

        List<DetailRow> rows = new ArrayList<>();
        rows.add(new DetailRow("Total", Format.formatKb(total)));
        rows.add(new DetailRow("Used", Format.formatKb(used), (double) used / total));
        rows.add(new DetailRow("Available", Format.formatKb(available), (double) available / total));
        rows.add(new DetailRow("Buffers", Format.formatKb(buffers)));
        rows.add(new DetailRow("Cached", Format.formatKb(cached)));
        if (swapTotal > 0) {
            long swapUsed = swapTotal - swapFree;
            rows.add(new DetailRow("Swap", Format.formatKb(swapUsed) + " / " + Format.formatKb(swapTotal),
                    (double) swapUsed / swapTotal));
        }

        return MetricReading.of(available / 1024.0, Format.formatKb(available), rows);
    }

    private static long get(Map<String, Long> m, String key) {
        Long v = m.get(key);
        return v != null ? v : 0L;
    }
}
