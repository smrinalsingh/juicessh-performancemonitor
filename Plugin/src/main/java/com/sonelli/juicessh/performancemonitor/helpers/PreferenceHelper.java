package com.sonelli.juicessh.performancemonitor.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

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
}
