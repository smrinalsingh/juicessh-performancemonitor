package com.sonelli.juicessh.performancemonitor.parsers;

import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parses {@code /proc/stat} into aggregate CPU busy% (headline) with one detail
 * row per core. CPU counters are cumulative since boot, so each value is a delta
 * against the previous tick; the first tick therefore yields no reading.
 */
public class CpuStatParser {

    // key -> {idle, total} from the previous tick. Key -1 = aggregate, 0..N = core index.
    private final Map<Integer, long[]> previous = new HashMap<>();

    /** @return the reading, or null if no delta is computable yet (first tick). */
    public MetricReading parse(List<String> lines) {
        final Map<Integer, long[]> current = new TreeMap<>();

        for (String line : lines) {
            if (!line.startsWith("cpu")) {
                continue;
            }
            String[] t = line.trim().split("\\s+");
            if (t.length < 6) {
                continue; // need at least user..iowait
            }
            String id = t[0].substring(3);
            int key;
            if (id.isEmpty()) {
                key = -1;
            } else {
                try {
                    key = Integer.parseInt(id);
                } catch (NumberFormatException e) {
                    continue;
                }
            }

            // Sum user..steal only (indices 1..8). guest (t[9]) and guest_nice
            // (t[10]) are already folded into user/nice by the kernel, so adding
            // them would double-count on VM hosts.
            long total = 0;
            int last = Math.min(t.length - 1, 8);
            for (int i = 1; i <= last; i++) {
                total += parse(t[i]);
            }
            long idle = parse(t[4]) + parse(t[5]); // idle + iowait
            current.put(key, new long[]{idle, total});
        }

        Double aggregate = percentBusy(previous.get(-1), current.get(-1));

        List<DetailRow> rows = new ArrayList<>();
        for (Map.Entry<Integer, long[]> entry : current.entrySet()) {
            if (entry.getKey() < 0) {
                continue;
            }
            Double pct = percentBusy(previous.get(entry.getKey()), entry.getValue());
            if (pct != null) {
                rows.add(new DetailRow(
                        "Core " + entry.getKey(),
                        String.format(Locale.US, "%.0f%%", pct),
                        pct / 100.0));
            }
        }

        previous.clear();
        previous.putAll(current);

        if (aggregate == null) {
            return null;
        }
        return MetricReading.of(aggregate, String.format(Locale.US, "%.0f%%", aggregate), rows);
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Busy percentage between two {idle,total} samples, or null if not computable yet. */
    private static Double percentBusy(long[] prev, long[] cur) {
        if (prev == null || cur == null) {
            return null;
        }
        long totalDelta = cur[1] - prev[1];
        long idleDelta = cur[0] - prev[0];
        if (totalDelta <= 0) {
            return null;
        }
        double busy = (1.0 - ((double) idleDelta / totalDelta)) * 100.0;
        if (busy < 0) busy = 0;
        if (busy > 100) busy = 100;
        return busy;
    }
}
