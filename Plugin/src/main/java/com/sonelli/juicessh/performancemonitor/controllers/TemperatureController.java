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
 * Reports the hottest thermal zone as the headline, with every sensor in the
 * detail sheet. Reads {@code /sys/class/thermal/*} (works on most Linux servers)
 * and falls back to the Raspberry Pi {@code vcgencmd} the original plugin used.
 * Respects the °C/°F unit preference.
 */
public class TemperatureController extends BaseController {

    public static final String TAG = "TemperatureController";

    private static final String COMMAND =
            "for z in /sys/class/thermal/thermal_zone*; do "
            + "echo \"$(cat $z/type 2>/dev/null)=$(cat $z/temp 2>/dev/null)\"; done 2>/dev/null; "
            + "/opt/vc/bin/vcgencmd measure_temp 2>/dev/null";

    // Captures "name=value" where value's first number is the temperature.
    private static final Pattern SENSOR = Pattern.compile("^([^=]+)=.*?(-?[0-9]+(?:\\.[0-9]+)?)");

    public TemperatureController(Context context) {
        super(context);
    }

    @Override
    public BaseController start() {
        super.start();

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {

                final List<double[]> sensors = new ArrayList<>(); // {celsius}
                final List<String> names = new ArrayList<>();

                try {
                    getPluginClient().executeCommandOnSession(getSessionId(), getSessionKey(), COMMAND, new OnSessionExecuteListener() {

                        @Override
                        public void onCompleted(int exitCode) {
                            if (sensors.isEmpty()) {
                                setText(getString(R.string.not_available));
                                return;
                            }

                            boolean fahrenheit = usesFahrenheit();
                            double hottest = -Double.MAX_VALUE;
                            List<DetailRow> rows = new ArrayList<>();
                            for (int i = 0; i < sensors.size(); i++) {
                                double c = sensors.get(i)[0];
                                if (c > hottest) {
                                    hottest = c;
                                }
                                rows.add(new DetailRow(names.get(i), formatTemp(c, fahrenheit)));
                            }

                            publish(hottest, formatTemp(hottest, fahrenheit));
                            setDetailRows(rows);
                        }

                        @Override
                        public void onOutputLine(String line) {
                            Matcher m = SENSOR.matcher(line.trim());
                            if (!m.find()) {
                                return;
                            }
                            String name = m.group(1).trim();
                            double raw;
                            try {
                                raw = Double.parseDouble(m.group(2));
                            } catch (NumberFormatException e) {
                                return;
                            }
                            // thermal_zone temps are millidegrees; vcgencmd is already °C.
                            double celsius = Math.abs(raw) > 200 ? raw / 1000.0 : raw;
                            names.add("temp".equalsIgnoreCase(name) ? "CPU" : name);
                            sensors.add(new double[]{celsius});
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

    private boolean usesFahrenheit() {
        return getPreferences() != null && getPreferences().useFahrenheit();
    }

    private static String formatTemp(double celsius, boolean fahrenheit) {
        if (fahrenheit) {
            return String.format(Locale.US, "%.0f°F", celsius * 9.0 / 5.0 + 32.0);
        }
        return String.format(Locale.US, "%.0f°C", celsius);
    }
}
