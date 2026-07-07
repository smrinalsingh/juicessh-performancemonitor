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

/**
 * Reads {@code /proc/loadavg} (more portable than parsing {@code uptime}) and
 * reports the 1-minute load average as the headline, with 1/5/15-minute averages
 * and running/total process counts in the detail sheet.
 */
public class LoadAverageController extends BaseController {

    public static final String TAG = "LoadAverageController";

    public LoadAverageController(Context context) {
        super(context);
    }

    @Override
    public BaseController start() {
        super.start();

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {

                try {
                    getPluginClient().executeCommandOnSession(getSessionId(), getSessionKey(), "cat /proc/loadavg", new OnSessionExecuteListener() {

                        @Override
                        public void onCompleted(int exitCode) {
                            if (exitCode == 127) {
                                setText(getString(R.string.error));
                                Log.d(TAG, "cat /proc/loadavg not available on server");
                            }
                        }

                        @Override
                        public void onOutputLine(String line) {
                            String[] t = line.trim().split("\\s+");
                            if (t.length < 3) {
                                return;
                            }
                            try {
                                double load1 = Double.parseDouble(t[0]);
                                publish(load1, String.format(Locale.US, "%.2f", load1));

                                List<DetailRow> rows = new ArrayList<>();
                                rows.add(new DetailRow("1 min", t[0]));
                                rows.add(new DetailRow("5 min", t[1]));
                                rows.add(new DetailRow("15 min", t[2]));
                                if (t.length >= 4 && t[3].contains("/")) {
                                    rows.add(new DetailRow("Processes (running/total)", t[3]));
                                }
                                setDetailRows(rows);
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
}
