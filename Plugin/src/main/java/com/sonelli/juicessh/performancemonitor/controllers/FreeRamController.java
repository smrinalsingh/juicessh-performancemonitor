package com.sonelli.juicessh.performancemonitor.controllers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.sonelli.juicessh.performancemonitor.R;
import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.pluginlibrary.exceptions.ServiceNotConnectedException;
import com.sonelli.juicessh.pluginlibrary.listeners.OnSessionExecuteListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code /proc/meminfo} and reports available RAM as the headline, with a
 * total/used/buffers/cached/swap breakdown in the detail sheet.
 */
public class FreeRamController extends BaseController {

    public static final String TAG = "FreeRamController";

    private static final Pattern LINE = Pattern.compile("^(\\w+):\\s*([0-9]+)");

    public FreeRamController(Context context) {
        super(context);
    }

    @Override
    public BaseController start() {
        super.start();

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {

                final Map<String, Long> mem = new HashMap<>();

                try {
                    getPluginClient().executeCommandOnSession(getSessionId(), getSessionKey(), "cat /proc/meminfo", new OnSessionExecuteListener() {

                        @Override
                        public void onCompleted(int exitCode) {
                            if (exitCode == 127) {
                                setText(getString(R.string.error));
                                Log.d(TAG, "cat /proc/meminfo not available on server");
                                return;
                            }

                            long total = get(mem, "MemTotal");
                            long free = get(mem, "MemFree");
                            long buffers = get(mem, "Buffers");
                            long cached = get(mem, "Cached");
                            long available = mem.containsKey("MemAvailable")
                                    ? get(mem, "MemAvailable")
                                    : free + buffers + cached;
                            long used = total - available;
                            long swapTotal = get(mem, "SwapTotal");
                            long swapFree = get(mem, "SwapFree");

                            if (total <= 0) {
                                return;
                            }

                            publish(available / 1024.0, formatKb(available));

                            List<DetailRow> rows = new ArrayList<>();
                            rows.add(new DetailRow("Total", formatKb(total)));
                            rows.add(new DetailRow("Used", formatKb(used), (double) used / total));
                            rows.add(new DetailRow("Available", formatKb(available), (double) available / total));
                            rows.add(new DetailRow("Buffers", formatKb(buffers)));
                            rows.add(new DetailRow("Cached", formatKb(cached)));
                            if (swapTotal > 0) {
                                long swapUsed = swapTotal - swapFree;
                                rows.add(new DetailRow("Swap", formatKb(swapUsed) + " / " + formatKb(swapTotal),
                                        (double) swapUsed / swapTotal));
                            }
                            setDetailRows(rows);
                        }

                        @Override
                        public void onOutputLine(String line) {
                            Matcher m = LINE.matcher(line);
                            if (m.find()) {
                                try {
                                    mem.put(m.group(1), Long.parseLong(m.group(2)));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }

                        @Override
                        public void onError(int error, String reason) {
                            toast(reason);
                        }
                    });
                } catch (ServiceNotConnectedException e) {
                    Log.d(TAG, "Could not connect to JuiceSSH plugin service");
                }

                if (isRunning()) {
                    handler.postDelayed(this, getIntervalMs());
                }
            }
        });

        return this;
    }

    private static long get(Map<String, Long> m, String key) {
        Long v = m.get(key);
        return v != null ? v : 0L;
    }

    /** Formats a kB figure (as reported by /proc/meminfo) into KB/MB/GB. */
    static String formatKb(long kb) {
        if (kb >= 1048576) {
            return String.format(Locale.US, "%.1f GB", kb / 1048576.0);
        } else if (kb >= 1024) {
            return String.format(Locale.US, "%.0f MB", kb / 1024.0);
        }
        return kb + " KB";
    }
}
