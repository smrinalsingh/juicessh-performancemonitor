package com.sonelli.juicessh.performancemonitor.helpers;

import java.util.Locale;

/**
 * Shared formatting for metric values. Pure Java so the parsers that use it can
 * run in plain JVM unit tests.
 */
public final class Format {

    private Format() {
    }

    /** Formats a kB figure (as reported by /proc/meminfo or df -Pk) into KB/MB/GB. */
    public static String formatKb(long kb) {
        if (kb >= 1048576) {
            return String.format(Locale.US, "%.1f GB", kb / 1048576.0);
        } else if (kb >= 1024) {
            return String.format(Locale.US, "%.0f MB", kb / 1024.0);
        }
        return kb + " KB";
    }

    /** Formats a bytes/second rate as bits (Kbps/Mbps) or bytes (KB/s/MB/s). */
    public static String formatRate(double bytesPerSecond, boolean bits) {
        if (bits) {
            double bps = bytesPerSecond * 8.0;
            if (bps >= 1_000_000) {
                return String.format(Locale.US, "%.1f Mbps", bps / 1_000_000);
            } else if (bps >= 1_000) {
                return String.format(Locale.US, "%.0f Kbps", bps / 1_000);
            }
            return "< 1 Kbps";
        }
        if (bytesPerSecond >= 1_048_576) {
            return String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1_048_576);
        } else if (bytesPerSecond >= 1_024) {
            return String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1_024);
        }
        return "< 1 KB/s";
    }

    public static String formatTemp(double celsius, boolean fahrenheit) {
        if (fahrenheit) {
            return String.format(Locale.US, "%.0f°F", celsius * 9.0 / 5.0 + 32.0);
        }
        return String.format(Locale.US, "%.0f°C", celsius);
    }
}
