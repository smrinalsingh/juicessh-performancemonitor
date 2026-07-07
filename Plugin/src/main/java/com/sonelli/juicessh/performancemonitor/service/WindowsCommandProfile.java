package com.sonelli.juicessh.performancemonitor.service;

import android.util.Base64;

import com.sonelli.juicessh.performancemonitor.helpers.PreferenceHelper;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;
import com.sonelli.juicessh.performancemonitor.model.MetricType;
import com.sonelli.juicessh.performancemonitor.parsers.windows.WinCpuParser;
import com.sonelli.juicessh.performancemonitor.parsers.windows.WinDiskParser;
import com.sonelli.juicessh.performancemonitor.parsers.windows.WinMemParser;
import com.sonelli.juicessh.performancemonitor.parsers.windows.WinNetParser;
import com.sonelli.juicessh.performancemonitor.parsers.windows.WinTempParser;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Metric collection for Windows servers whose SSH shell is PowerShell: one
 * compound command using CIM/performance-counter classes, emitting the same
 * {@code ###PMON:} sections as the Linux profile. CPU, RAM, Disk and Network
 * map cleanly; Load average has no Windows equivalent and Temperature usually
 * requires ACPI/admin, so both fall back to an explanatory "unavailable".
 */
public class WindowsCommandProfile implements CommandProfile {

    static final String WINDOWS_UNAVAILABLE = "Not available on Windows";

    // The PowerShell script that gathers the metrics. Each statement writes plain
    // strings (never objects), so output is one clean line per item with no
    // PowerShell table headers to strip.
    private static final String SCRIPT =
            "echo '###PMON:cpu'; "
            + "Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor | "
            + "ForEach-Object { \"$($_.Name)=$($_.PercentProcessorTime)\" }; "
            + "echo '###PMON:mem'; "
            + "$o=Get-CimInstance Win32_OperatingSystem; "
            + "\"free=$($o.FreePhysicalMemory)\"; \"total=$($o.TotalVisibleMemorySize)\"; "
            + "echo '###PMON:disk'; "
            + "Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3' | "
            + "ForEach-Object { \"$($_.DeviceID)|$($_.Size)|$($_.FreeSpace)\" }; "
            + "echo '###PMON:net'; "
            + "Get-CimInstance Win32_PerfFormattedData_Tcpip_NetworkInterface | "
            + "ForEach-Object { \"$($_.Name)|$($_.BytesReceivedPersec)|$($_.BytesSentPersec)\" }; "
            + "echo '###PMON:temp'; "
            + "try { Get-CimInstance -Namespace root/wmi -ClassName MSAcpi_ThermalZoneTemperature -ErrorAction Stop | "
            + "ForEach-Object { $_.CurrentTemperature } } catch { }";

    // Windows OpenSSH's default shell is cmd.exe, and even a PowerShell shell can
    // vary, so we don't send the script to the SSH shell directly — we launch
    // powershell.exe explicitly. Passing the script as -EncodedCommand (Base64 of
    // its UTF-16LE bytes, no BOM) sidesteps all cross-shell quoting, so the same
    // one-line command runs identically from cmd.exe or PowerShell.
    private static final String COMMAND =
            "powershell -NoProfile -NonInteractive -EncodedCommand "
            + Base64.encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE), Base64.NO_WRAP);

    private final WinCpuParser cpuParser = new WinCpuParser();
    private final WinMemParser memParser = new WinMemParser();
    private final WinDiskParser diskParser = new WinDiskParser();
    private final WinNetParser netParser = new WinNetParser();
    private final WinTempParser tempParser = new WinTempParser();

    @Override
    public String command() {
        return COMMAND;
    }

    @Override
    public EnumMap<MetricType, MetricReading> parse(Map<String, List<String>> sections, long nowMs, PreferenceHelper prefs) {
        EnumMap<MetricType, MetricReading> readings = new EnumMap<>(MetricType.class);

        putParsed(readings, MetricType.CPU, sections.get("cpu"), cpuParser.parse(nonNull(sections.get("cpu"))));
        putParsed(readings, MetricType.RAM, sections.get("mem"), memParser.parse(nonNull(sections.get("mem"))));
        putParsed(readings, MetricType.DISK, sections.get("disk"), diskParser.parse(nonNull(sections.get("disk"))));
        putParsed(readings, MetricType.NETWORK, sections.get("net"),
                netParser.parse(nonNull(sections.get("net")), prefs.useBits()));

        // Temperature: best-effort — present but usually blocked on Windows.
        List<String> temp = sections.get("temp");
        MetricReading tempReading = (temp == null || temp.isEmpty()) ? null : tempParser.parse(temp, prefs.useFahrenheit());
        readings.put(MetricType.TEMPERATURE,
                tempReading != null ? tempReading : MetricReading.unavailable(WINDOWS_UNAVAILABLE));

        // Load average is a Unix concept; Windows has no equivalent.
        readings.put(MetricType.LOAD, MetricReading.unavailable(WINDOWS_UNAVAILABLE));

        return readings;
    }

    private static List<String> nonNull(List<String> section) {
        return section != null ? section : java.util.Collections.<String>emptyList();
    }

    /** Missing section ⇒ absent (warm-up); present-but-unparseable ⇒ unavailable. */
    private static void putParsed(EnumMap<MetricType, MetricReading> readings, MetricType type,
                                  List<String> section, MetricReading parsed) {
        if (section == null || section.isEmpty()) {
            readings.put(type, MetricReading.unavailable());
        } else if (parsed != null) {
            readings.put(type, parsed);
        }
    }
}
