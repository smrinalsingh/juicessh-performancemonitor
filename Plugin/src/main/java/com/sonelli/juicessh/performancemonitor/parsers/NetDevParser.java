package com.sonelli.juicessh.performancemonitor.parsers;

import com.sonelli.juicessh.performancemonitor.helpers.Format;
import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses {@code /proc/net/dev} into total throughput (rx+tx bytes/second,
 * headline) with per-interface rates as detail rows. Loopback is excluded.
 * Counters are cumulative, so rates are deltas over wall-clock time and the
 * first tick yields no reading. The clock is injected for testability.
 */
public class NetDevParser {

    private final Map<String, Long> lastBytes = new HashMap<>();
    private long lastCheck = 0;

    /**
     * @param nowMs wall-clock time of this tick
     * @param bits  format rates as bits (Kbps/Mbps) instead of bytes
     * @return the reading (value = total bytes/second), or null until two ticks
     * at least 0.5s apart have been seen.
     */
    public MetricReading parse(List<String> lines, long nowMs, boolean bits) {
        final Map<String, Long> current = new HashMap<>();

        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            if (name.isEmpty() || name.equals("lo")) {
                continue;
            }
            String[] nums = line.substring(colon + 1).trim().split("\\s+");
            if (nums.length < 9) {
                continue;
            }
            try {
                long rx = Long.parseLong(nums[0]);
                long tx = Long.parseLong(nums[8]);
                current.put(name, rx + tx);
            } catch (NumberFormatException ignored) {
            }
        }

        MetricReading reading = null;
        double seconds = (nowMs - lastCheck) / 1000.0;

        if (lastCheck != 0 && seconds >= 0.5) {
            double totalRate = 0;
            List<DetailRow> rows = new ArrayList<>();
            for (Map.Entry<String, Long> e : current.entrySet()) {
                Long prev = lastBytes.get(e.getKey());
                if (prev == null) {
                    continue;
                }
                double rate = Math.max(0, (e.getValue() - prev) / seconds);
                totalRate += rate;
                rows.add(new DetailRow(e.getKey(), Format.formatRate(rate, bits)));
            }
            reading = MetricReading.of(totalRate, Format.formatRate(totalRate, bits), rows);
        }

        lastBytes.clear();
        lastBytes.putAll(current);
        lastCheck = nowMs;

        return reading;
    }
}
