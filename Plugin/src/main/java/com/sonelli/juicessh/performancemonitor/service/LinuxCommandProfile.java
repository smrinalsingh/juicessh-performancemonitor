package com.sonelli.juicessh.performancemonitor.service;

import com.sonelli.juicessh.performancemonitor.helpers.PreferenceHelper;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;
import com.sonelli.juicessh.performancemonitor.model.MetricType;
import com.sonelli.juicessh.performancemonitor.parsers.CpuStatParser;
import com.sonelli.juicessh.performancemonitor.parsers.DfParser;
import com.sonelli.juicessh.performancemonitor.parsers.LoadAvgParser;
import com.sonelli.juicessh.performancemonitor.parsers.MemInfoParser;
import com.sonelli.juicessh.performancemonitor.parsers.NetDevParser;
import com.sonelli.juicessh.performancemonitor.parsers.ThermalParser;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Metric collection for Linux (and other {@code /proc}-based) servers: one
 * compound command reading {@code /proc/stat}, {@code /proc/meminfo},
 * {@code /proc/loadavg}, {@code /proc/net/dev}, {@code /sys/class/thermal} and
 * {@code df}, each in its own {@code ###PMON:} section.
 */
public class LinuxCommandProfile implements CommandProfile {

    private static final String COMMAND =
            "echo '###PMON:cpu'; cat /proc/stat 2>/dev/null; "
            + "echo '###PMON:mem'; cat /proc/meminfo 2>/dev/null; "
            + "echo '###PMON:load'; cat /proc/loadavg 2>/dev/null; "
            + "echo '###PMON:net'; cat /proc/net/dev 2>/dev/null; "
            + "echo '###PMON:temp'; for z in /sys/class/thermal/thermal_zone*; do "
            + "echo \"$(cat $z/type 2>/dev/null)=$(cat $z/temp 2>/dev/null)\"; done 2>/dev/null; "
            + "/opt/vc/bin/vcgencmd measure_temp 2>/dev/null; "
            + "echo '###PMON:disk'; df -Pk 2>/dev/null";

    // Parsers keep cross-tick state (CPU/network deltas), so they are per-instance.
    private final CpuStatParser cpuParser = new CpuStatParser();
    private final MemInfoParser memParser = new MemInfoParser();
    private final LoadAvgParser loadParser = new LoadAvgParser();
    private final NetDevParser netParser = new NetDevParser();
    private final ThermalParser thermalParser = new ThermalParser();
    private final DfParser dfParser = new DfParser();

    @Override
    public String command() {
        return COMMAND;
    }

    @Override
    public EnumMap<MetricType, MetricReading> parse(Map<String, List<String>> sections, long nowMs, PreferenceHelper prefs) {
        EnumMap<MetricType, MetricReading> readings = new EnumMap<>(MetricType.class);
        put(readings, MetricType.CPU, sections.get("cpu"), s -> cpuParser.parse(s));
        put(readings, MetricType.RAM, sections.get("mem"), s -> memParser.parse(s));
        put(readings, MetricType.LOAD, sections.get("load"), s -> loadParser.parse(s));
        put(readings, MetricType.NETWORK, sections.get("net"), s -> netParser.parse(s, nowMs, prefs.useBits()));
        put(readings, MetricType.DISK, sections.get("disk"), s -> dfParser.parse(s));

        // Temperature: unlike the delta-based metrics, "nothing parseable" means the
        // server has no readable sensors, not a warm-up tick — report N/A.
        List<String> temp = sections.get("temp");
        if (temp == null || temp.isEmpty()) {
            readings.put(MetricType.TEMPERATURE, MetricReading.unavailable());
        } else {
            MetricReading reading = thermalParser.parse(temp, prefs.useFahrenheit());
            readings.put(MetricType.TEMPERATURE, reading != null ? reading : MetricReading.unavailable());
        }
        return readings;
    }

    private interface SectionParser {
        MetricReading parse(List<String> lines);
    }

    /** Empty/missing section ⇒ data source missing on the server ⇒ unavailable. */
    private static void put(EnumMap<MetricType, MetricReading> readings, MetricType type,
                            List<String> section, SectionParser parser) {
        if (section == null || section.isEmpty()) {
            readings.put(type, MetricReading.unavailable());
            return;
        }
        MetricReading reading = parser.parse(section);
        if (reading != null) {
            readings.put(type, reading);
        }
    }
}
