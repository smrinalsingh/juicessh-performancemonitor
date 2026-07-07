package com.sonelli.juicessh.performancemonitor.parsers;

import com.sonelli.juicessh.performancemonitor.helpers.Format;
import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the thermal section of the batched command: one {@code name=value}
 * line per {@code /sys/class/thermal} zone, plus the Raspberry Pi
 * {@code vcgencmd measure_temp} format ({@code temp=48.3'C}). Reports the
 * hottest sensor as the headline (value always °C; display honours the °C/°F
 * preference) with every sensor as a detail row.
 */
public class ThermalParser {

    // Captures "name=value" where value's first number is the temperature.
    private static final Pattern SENSOR = Pattern.compile("^([^=]+)=.*?(-?[0-9]+(?:\\.[0-9]+)?)");

    /** @return the reading (value = hottest °C), or null if no sensor was parseable. */
    public MetricReading parse(List<String> lines, boolean fahrenheit) {
        final List<Double> sensors = new ArrayList<>();
        final List<String> names = new ArrayList<>();

        for (String line : lines) {
            Matcher m = SENSOR.matcher(line.trim());
            if (!m.find()) {
                continue;
            }
            String name = m.group(1).trim();
            double raw;
            try {
                raw = Double.parseDouble(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            // thermal_zone temps are millidegrees; vcgencmd is already °C.
            double celsius = Math.abs(raw) > 200 ? raw / 1000.0 : raw;
            names.add("temp".equalsIgnoreCase(name) ? "CPU" : name);
            sensors.add(celsius);
        }

        if (sensors.isEmpty()) {
            return null;
        }

        double hottest = -Double.MAX_VALUE;
        List<DetailRow> rows = new ArrayList<>();
        for (int i = 0; i < sensors.size(); i++) {
            double c = sensors.get(i);
            if (c > hottest) {
                hottest = c;
            }
            rows.add(new DetailRow(names.get(i), Format.formatTemp(c, fahrenheit)));
        }

        return MetricReading.of(hottest, Format.formatTemp(hottest, fahrenheit), rows);
    }
}
