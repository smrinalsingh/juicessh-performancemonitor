package com.sonelli.juicessh.performancemonitor.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.sonelli.juicessh.performancemonitor.helpers.PreferenceHelper;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;
import com.sonelli.juicessh.performancemonitor.model.MetricSnapshot;
import com.sonelli.juicessh.performancemonitor.model.MetricType;
import com.sonelli.juicessh.performancemonitor.parsers.SectionSplitter;
import com.sonelli.juicessh.pluginlibrary.PluginClient;
import com.sonelli.juicessh.pluginlibrary.exceptions.ServiceNotConnectedException;
import com.sonelli.juicessh.pluginlibrary.listeners.OnSessionExecuteListener;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single poll loop behind all metric tiles. Each tick runs ONE compound
 * command over the JuiceSSH session (six data sources separated by
 * {@code ###PMON:} markers) instead of six separate SSH executions, parses each
 * section, and emits a {@link MetricSnapshot} sharing a single timestamp.
 *
 * <p>Runs on the main looper like the old per-metric controllers. An in-flight
 * guard stops a slow tick from stacking executions; a watchdog abandons a tick
 * that never completes (e.g. {@code df} hung on a stale NFS mount — which is why
 * df is ordered last) after 3× the poll interval.
 */
public class MonitoringEngine {

    public static final String TAG = "MonitoringEngine";

    /** How many intervals an in-flight tick may take before it is abandoned. */
    private static final int WATCHDOG_INTERVALS = 3;

    public interface Listener {
        /** A tick completed. Called on the main thread. */
        void onSnapshot(MetricSnapshot snapshot);

        /** The session reported an execution error. Called on the main thread. */
        void onEngineError(String reason);
    }

    private final Context context;
    private final PluginClient client;
    private final int sessionId;
    private final String sessionKey;
    private final String connectionId;
    private final CommandProfile profile;
    private final Listener listener;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Tick bookkeeping (main thread only). The generation counter invalidates
    // callbacks from a tick that was abandoned by the watchdog or by stop().
    private int generation = 0;
    private boolean inFlight = false;
    private long tickStartMs = 0;

    public MonitoringEngine(Context context, PluginClient client, int sessionId, String sessionKey,
                            String connectionId, CommandProfile profile, Listener listener) {
        this.context = context.getApplicationContext();
        this.client = client;
        this.sessionId = sessionId;
        this.sessionKey = sessionKey;
        this.connectionId = connectionId;
        this.profile = profile;
        this.listener = listener;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public boolean isRunning() {
        return running.get();
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running.get()) {
                return;
            }
            long interval = getIntervalMs();
            long now = System.currentTimeMillis();
            if (!inFlight) {
                startTick();
            } else if (now - tickStartMs >= WATCHDOG_INTERVALS * interval) {
                Log.w(TAG, "Poll tick did not complete within " + WATCHDOG_INTERVALS + " intervals; abandoning it");
                startTick(); // bumps the generation, so the stale tick's callbacks are ignored
            }
            handler.postDelayed(this, interval);
        }
    };

    private void startTick() {
        inFlight = true;
        tickStartMs = System.currentTimeMillis();
        final int gen = ++generation;
        final List<String> lines = new ArrayList<>();

        try {
            client.executeCommandOnSession(sessionId, sessionKey, profile.command(), new OnSessionExecuteListener() {
                @Override
                public void onOutputLine(String line) {
                    lines.add(line);
                }

                @Override
                public void onCompleted(int exitCode) {
                    runOnEngineThread(() -> completeTick(gen, lines));
                }

                @Override
                public void onError(int error, String reason) {
                    runOnEngineThread(() -> {
                        if (gen == generation) {
                            inFlight = false;
                        }
                        if (running.get()) {
                            listener.onEngineError(reason);
                        }
                    });
                }
            });
        } catch (ServiceNotConnectedException e) {
            inFlight = false;
            Log.d(TAG, "Could not connect to JuiceSSH plugin service");
        }
    }

    private void runOnEngineThread(Runnable r) {
        if (Looper.myLooper() == handler.getLooper()) {
            r.run();
        } else {
            handler.post(r);
        }
    }

    private void completeTick(int gen, List<String> lines) {
        if (!running.get() || gen != generation) {
            return; // stopped, or abandoned by the watchdog
        }
        inFlight = false;

        Map<String, List<String>> sections = SectionSplitter.split(lines);
        long now = System.currentTimeMillis();
        EnumMap<MetricType, MetricReading> readings =
                profile.parse(sections, now, new PreferenceHelper(context));
        listener.onSnapshot(new MetricSnapshot(now, connectionId, readings));
    }

    /** Current polling interval, read fresh each tick so Settings changes apply live. */
    protected long getIntervalMs() {
        return new PreferenceHelper(context).getRefreshIntervalMs();
    }

    public MonitoringEngine start() {
        if (running.getAndSet(true)) {
            return this;
        }
        handler.post(pollRunnable);
        return this;
    }

    public void stop() {
        running.set(false);
        handler.removeCallbacks(pollRunnable);
        // Invalidate any in-flight tick so late callbacks can't publish stale data.
        runOnEngineThread(() -> {
            generation++;
            inFlight = false;
        });
    }
}
