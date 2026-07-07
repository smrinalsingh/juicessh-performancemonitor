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

/**
 * Reads {@code /proc/net/dev} and reports total throughput (rx+tx) as the
 * headline, with per-interface rates in the detail sheet. Loopback is excluded.
 * Respects the bytes/bits unit preference. Rates are deltas over wall-clock time.
 */
public class NetworkUsageController extends BaseController {

    public static final String TAG = "NetworkUsageController";

    private final Map<String, Long> lastBytes = new HashMap<>();
    private long lastCheck = 0;

    public NetworkUsageController(Context context) {
        super(context);
    }

    @Override
    public BaseController start() {
        super.start();

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {

                final Map<String, Long> current = new HashMap<>();

                try {
                    getPluginClient().executeCommandOnSession(getSessionId(), getSessionKey(), "cat /proc/net/dev", new OnSessionExecuteListener() {

                        @Override
                        public void onCompleted(int exitCode) {
                            if (exitCode == 127) {
                                setText(getString(R.string.error));
                                Log.d(TAG, "cat /proc/net/dev not available on server");
                                return;
                            }

                            long now = System.currentTimeMillis();
                            double seconds = (now - lastCheck) / 1000.0;

                            if (lastCheck != 0 && seconds >= 0.5) {
                                boolean bits = usesBits();
                                double totalRate = 0;
                                List<DetailRow> rows = new ArrayList<>();
                                for (Map.Entry<String, Long> e : current.entrySet()) {
                                    Long prev = lastBytes.get(e.getKey());
                                    if (prev == null) {
                                        continue;
                                    }
                                    double rate = Math.max(0, (e.getValue() - prev) / seconds);
                                    totalRate += rate;
                                    rows.add(new DetailRow(e.getKey(), formatRate(rate, bits)));
                                }
                                publish(totalRate, formatRate(totalRate, bits));
                                setDetailRows(rows);
                            }

                            lastBytes.clear();
                            lastBytes.putAll(current);
                            lastCheck = now;
                        }

                        @Override
                        public void onOutputLine(String line) {
                            int colon = line.indexOf(':');
                            if (colon < 0) {
                                return;
                            }
                            String name = line.substring(0, colon).trim();
                            if (name.isEmpty() || name.equals("lo")) {
                                return;
                            }
                            String[] nums = line.substring(colon + 1).trim().split("\\s+");
                            if (nums.length < 9) {
                                return;
                            }
                            try {
                                long rx = Long.parseLong(nums[0]);
                                long tx = Long.parseLong(nums[8]);
                                current.put(name, rx + tx);
                            } catch (NumberFormatException ignored) {
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

    private boolean usesBits() {
        return getPreferences() != null && getPreferences().useBits();
    }

    /** Formats a bytes/second rate as bits (Kbps/Mbps) or bytes (KB/s/MB/s). */
    static String formatRate(double bytesPerSecond, boolean bits) {
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
}
