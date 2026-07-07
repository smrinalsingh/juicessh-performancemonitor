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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs {@code df -P} and reports the usage of the root filesystem as the
 * headline, with every real mount in the detail sheet. Pseudo filesystems
 * (tmpfs/devtmpfs) are skipped.
 */
public class DiskUsageController extends BaseController {

    public static final String TAG = "DiskUsageController";

    // filesystem  1024-blocks  used  available  capacity%  mounted-on
    private static final Pattern ROW =
            Pattern.compile("^(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)%\\s+(.+)$");

    public DiskUsageController(Context context) {
        super(context);
    }

    @Override
    public BaseController start() {
        super.start();

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {

                final List<DetailRow> rows = new ArrayList<>();
                final double[] rootPct = {Double.NaN};

                try {
                    // -Pk forces POSIX output in 1024-byte blocks so the size/used columns
                    // are always KiB (matching formatKb), regardless of POSIXLY_CORRECT.
                    getPluginClient().executeCommandOnSession(getSessionId(), getSessionKey(), "df -Pk", new OnSessionExecuteListener() {

                        @Override
                        public void onCompleted(int exitCode) {
                            if (exitCode == 127) {
                                setText(getString(R.string.error));
                                Log.d(TAG, "df not available on server");
                                return;
                            }
                            if (!Double.isNaN(rootPct[0])) {
                                publish(rootPct[0], String.format(Locale.US, "%.0f%%", rootPct[0]));
                            }
                            setDetailRows(rows);
                        }

                        @Override
                        public void onOutputLine(String line) {
                            Matcher m = ROW.matcher(line.trim());
                            if (!m.find()) {
                                return; // header or unmatched line
                            }
                            String fs = m.group(1);
                            if (fs.equals("tmpfs") || fs.equals("devtmpfs") || fs.equals("none")) {
                                return;
                            }
                            long sizeKb = Long.parseLong(m.group(2));
                            long usedKb = Long.parseLong(m.group(3));
                            double pct = Double.parseDouble(m.group(5));
                            String mount = m.group(6).trim();
                            if (sizeKb <= 0) {
                                return;
                            }
                            if (mount.equals("/")) {
                                rootPct[0] = pct;
                            }
                            rows.add(new DetailRow(mount,
                                    String.format(Locale.US, "%.0f%%  (%s / %s)", pct,
                                            FreeRamController.formatKb(usedKb), FreeRamController.formatKb(sizeKb)),
                                    pct / 100.0));
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
}
