package com.sonelli.juicessh.performancemonitor.parsers.windows;

import com.sonelli.juicessh.performancemonitor.helpers.Format;
import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code MSAcpi_ThermalZoneTemperature.CurrentTemperature} values (tenths
 * of a Kelvin), one per line. Often empty/unavailable on Windows (the WMI class
 * needs ACPI support and admin rights); the caller substitutes a reason then.
 * Reports the hottest zone.
 */
public class WinTempParser {

    public MetricReading parse(List<String> lines, boolean fahrenheit) {
        List<Double> celsius = new ArrayList<>();
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                double deciKelvin = Double.parseDouble(s);
                double c = deciKelvin / 10.0 - 273.15;
                // Guard against obviously bogus readings (0 K etc.).
                if (c > -50 && c < 200) {
                    celsius.add(c);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (celsius.isEmpty()) {
            return null;
        }

        double hottest = -Double.MAX_VALUE;
        List<DetailRow> rows = new ArrayList<>();
        for (int i = 0; i < celsius.size(); i++) {
            double c = celsius.get(i);
            if (c > hottest) {
                hottest = c;
            }
            rows.add(new DetailRow("Zone " + i, Format.formatTemp(c, fahrenheit)));
        }
        return MetricReading.of(hottest, Format.formatTemp(hottest, fahrenheit), rows);
    }
}
