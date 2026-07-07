package com.sonelli.juicessh.performancemonitor.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.sonelli.juicessh.performancemonitor.model.AlertRule;
import com.sonelli.juicessh.performancemonitor.model.MetricType;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed accessor over the app's default SharedPreferences. Centralises the
 * preference keys and their defaults so activities, the settings screen and the
 * controllers all agree on them.
 */
public class PreferenceHelper {

    public static final String KEEP_SCREEN_ON_KEY = "keep_screen_on_key";
    public static final String REFRESH_INTERVAL_KEY = "refresh_interval_seconds";
    public static final String TEMP_UNIT_KEY = "temperature_unit";   // "c" | "f"
    public static final String NETWORK_UNIT_KEY = "network_unit";    // "bytes" | "bits"
    public static final String THEME_KEY = "theme_mode";             // "system" | "light" | "dark"

    // Alerts. Thresholds use the metrics' stored value semantics (CPU/disk %,
    // RAM available MB, temperature °C regardless of display unit, 1-min load).
    public static final String ALERT_CPU_ENABLED = "alert_cpu_enabled";
    public static final String ALERT_CPU_THRESHOLD = "alert_cpu_threshold";
    public static final String ALERT_RAM_ENABLED = "alert_ram_enabled";
    public static final String ALERT_RAM_THRESHOLD = "alert_ram_threshold_mb";
    public static final String ALERT_TEMP_ENABLED = "alert_temp_enabled";
    public static final String ALERT_TEMP_THRESHOLD = "alert_temp_threshold_c";
    public static final String ALERT_DISK_ENABLED = "alert_disk_enabled";
    public static final String ALERT_DISK_THRESHOLD = "alert_disk_threshold_pct";
    public static final String ALERT_LOAD_ENABLED = "alert_load_enabled";
    public static final String ALERT_LOAD_THRESHOLD = "alert_load_threshold";
    public static final String ALERT_SUSTAIN_KEY = "alert_sustain_seconds";
    public static final String ALERT_COOLDOWN_KEY = "alert_cooldown_minutes";

    public static final double DEFAULT_CPU_THRESHOLD = 90;    // % busy
    public static final double DEFAULT_RAM_THRESHOLD = 256;   // MB available
    public static final double DEFAULT_TEMP_THRESHOLD = 80;   // °C
    public static final double DEFAULT_DISK_THRESHOLD = 90;   // % used
    public static final double DEFAULT_LOAD_THRESHOLD = 4.0;  // 1-min load

    private final SharedPreferences prefs;

    public PreferenceHelper(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public void setKeepScreenOnFlag(boolean flag) {
        prefs.edit().putBoolean(KEEP_SCREEN_ON_KEY, flag).apply();
    }

    public boolean getKeepScreenOnFlag() {
        return prefs.getBoolean(KEEP_SCREEN_ON_KEY, false);
    }

    /** Polling interval in milliseconds; read fresh each tick so changes apply live. */
    public long getRefreshIntervalMs() {
        long seconds;
        try {
            seconds = Long.parseLong(prefs.getString(REFRESH_INTERVAL_KEY, "2"));
        } catch (NumberFormatException e) {
            seconds = 2;
        }
        if (seconds < 1) {
            seconds = 1;
        }
        return seconds * 1000L;
    }

    public boolean useFahrenheit() {
        return "f".equals(prefs.getString(TEMP_UNIT_KEY, "c"));
    }

    public boolean useBits() {
        return "bits".equals(prefs.getString(NETWORK_UNIT_KEY, "bytes"));
    }

    public String getThemeMode() {
        return prefs.getString(THEME_KEY, "system");
    }

    /** The alert rules that are currently switched on, ready for evaluation. */
    public List<AlertRule> getEnabledAlertRules() {
        List<AlertRule> rules = new ArrayList<>();
        if (prefs.getBoolean(ALERT_CPU_ENABLED, false)) {
            rules.add(new AlertRule(MetricType.CPU, getThreshold(ALERT_CPU_THRESHOLD, DEFAULT_CPU_THRESHOLD), false));
        }
        if (prefs.getBoolean(ALERT_RAM_ENABLED, false)) {
            rules.add(new AlertRule(MetricType.RAM, getThreshold(ALERT_RAM_THRESHOLD, DEFAULT_RAM_THRESHOLD), true));
        }
        if (prefs.getBoolean(ALERT_TEMP_ENABLED, false)) {
            rules.add(new AlertRule(MetricType.TEMPERATURE, getThreshold(ALERT_TEMP_THRESHOLD, DEFAULT_TEMP_THRESHOLD), false));
        }
        if (prefs.getBoolean(ALERT_DISK_ENABLED, false)) {
            rules.add(new AlertRule(MetricType.DISK, getThreshold(ALERT_DISK_THRESHOLD, DEFAULT_DISK_THRESHOLD), false));
        }
        if (prefs.getBoolean(ALERT_LOAD_ENABLED, false)) {
            rules.add(new AlertRule(MetricType.LOAD, getThreshold(ALERT_LOAD_THRESHOLD, DEFAULT_LOAD_THRESHOLD), false));
        }
        return rules;
    }

    private double getThreshold(String key, double fallback) {
        try {
            return Double.parseDouble(prefs.getString(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** How long a threshold must stay breached before its alert fires. */
    public long getAlertSustainMs() {
        try {
            return Long.parseLong(prefs.getString(ALERT_SUSTAIN_KEY, "30")) * 1000L;
        } catch (NumberFormatException e) {
            return 30_000L;
        }
    }

    /** Minimum gap between two firings of the same alert. */
    public long getAlertCooldownMs() {
        try {
            return Long.parseLong(prefs.getString(ALERT_COOLDOWN_KEY, "5")) * 60_000L;
        } catch (NumberFormatException e) {
            return 300_000L;
        }
    }
}
