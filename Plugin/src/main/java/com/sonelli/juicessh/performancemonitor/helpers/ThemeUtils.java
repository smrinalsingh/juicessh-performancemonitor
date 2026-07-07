package com.sonelli.juicessh.performancemonitor.helpers;

import androidx.appcompat.app.AppCompatDelegate;

/** Maps the stored theme preference ("system"/"light"/"dark") to a night mode. */
public final class ThemeUtils {

    private ThemeUtils() {
    }

    public static void apply(String mode) {
        if ("light".equals(mode)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else if ("dark".equals(mode)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }
}
