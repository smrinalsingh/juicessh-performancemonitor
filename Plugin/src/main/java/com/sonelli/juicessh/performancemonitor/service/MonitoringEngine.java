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
 * <p>The poll loop and the parse run on the shared background looper supplied at
 * construction (never the main thread), so N connected servers no longer parse N
 * SSH transcripts on the UI thread every interval. Only {@link Listener}
 * delivery is marshalled back to the main thread. An in-flight guard stops a slow
 * tick from stacking executions; a watchdog abandons a tick that never completes
 * (e.g. {@code df} hung on a stale NFS mount — which is why df is ordered last)
 * after 3× the poll interval.
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

    /** Supplies the current poll interval for this engine (active vs. background cadence). */
    public interface IntervalProvider {
        long getIntervalMs();
    }

    private final Context context;
    private final PluginClient client;
    private final int sessionId;
    private final String sessionKey;
    private final String connectionId;
    private final CommandProfile profile;
    private final Listener listener;
    private final IntervalProvider intervalProvider;

    /** Poll loop + parse run here (shared background looper). */
    private final Handler handler;
    /** Listener callbacks are delivered here so shared state stays main-thread-only. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Tick bookkeeping (engine looper only). The generation counter invalidates
    // callbacks from a tick that was abandoned by the watchdog or by stop().
    private int generation = 0;
    private boolean inFlight = false;
    private long tickStartMs = 0;

    public MonitoringEngine(Context context, PluginClient client, int sessionId, String sessionKey,
                            String connectionId, CommandProfile profile, Listener listener,
                            Looper engineLooper, IntervalProvider intervalProvider) {
        this.context = context.getApplicationContext();
        this.client = client;
        this.sessionId = sessionId;
        this.sessionKey = sessionKey;
        this.connectionId = connectionId;
        this.profile = profile;
        this.listener = listener;
        this.handler = new Handler(engineLooper);
        this.intervalProvider = intervalProvider;
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
                            deliver(() -> listener.onEngineError(reason));
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

    /** Delivers a listener callback on the main thread, dropping it if the engine has stopped. */
    private void deliver(Runnable r) {
        mainHandler.post(() -> {
            if (running.get()) {
                r.run();
            }
        });
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
        final MetricSnapshot snapshot = new MetricSnapshot(now, connectionId, readings);
        deliver(() -> listener.onSnapshot(snapshot));
    }

    /** Current polling interval; the provider decides active vs. background cadence. */
    protected long getIntervalMs() {
        return intervalProvider.getIntervalMs();
    }

    public MonitoringEngine start() {
        if (running.getAndSet(true)) {
            return this;
        }
        handler.post(pollRunnable);
        return this;
    }

    /**
     * Runs a tick immediately, resetting the interval clock. Called when a throttled
     * (background-cadence) server becomes active so it refreshes at once instead of
     * waiting out the slower background interval.
     */
    public void pollNow() {
        if (!running.get()) {
            return;
        }
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
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
