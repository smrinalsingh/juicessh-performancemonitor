package com.sonelli.juicessh.performancemonitor.service;

import com.sonelli.juicessh.performancemonitor.helpers.PreferenceHelper;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;
import com.sonelli.juicessh.performancemonitor.model.MetricType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * An OS-specific way to gather metrics: the single batched shell command to run
 * each tick, and how to turn its {@code ###PMON:}-delimited sections into
 * readings. {@link MonitoringEngine} is generic over this so the same poll loop
 * drives Linux ({@link LinuxCommandProfile}) and Windows/PowerShell
 * ({@link WindowsCommandProfile}) servers.
 *
 * <p>Implementations hold per-session parser state (CPU/network deltas), so each
 * monitored server gets its own instance.
 */
public interface CommandProfile {

    /** The compound command whose output is split on {@code ###PMON:<section>} markers. */
    String command();

    /**
     * Parses the split sections into readings. Metrics with no data this tick are
     * simply absent from the map; metrics the OS cannot provide are present as
     * {@link MetricReading#unavailable(String)}.
     */
    EnumMap<MetricType, MetricReading> parse(Map<String, List<String>> sections, long nowMs, PreferenceHelper prefs);
}
