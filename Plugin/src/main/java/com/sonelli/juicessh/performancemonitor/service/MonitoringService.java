package com.sonelli.juicessh.performancemonitor.service;

import android.Manifest;
import android.app.Notification;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import com.sonelli.juicessh.performancemonitor.R;
import com.sonelli.juicessh.performancemonitor.helpers.Format;
import com.sonelli.juicessh.performancemonitor.helpers.MetricLabels;
import com.sonelli.juicessh.performancemonitor.helpers.PreferenceHelper;
import com.sonelli.juicessh.performancemonitor.model.AlertRule;
import com.sonelli.juicessh.performancemonitor.model.MetricSnapshot;
import com.sonelli.juicessh.performancemonitor.model.MetricType;
import com.sonelli.juicessh.pluginlibrary.PluginClient;
import com.sonelli.juicessh.pluginlibrary.exceptions.ServiceNotConnectedException;
import com.sonelli.juicessh.pluginlibrary.listeners.OnClientStartedListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Foreground service that owns the app's shared {@link PluginClient} and one
 * {@link SessionMonitor} per connected server, so several servers are polled at
 * once and each keeps recording history and firing alerts in the background —
 * the dashboard just shows whichever is {@link #activeConnectionId active}.
 *
 * <p>{@code MainActivity} binds for its whole lifetime (the JuiceSSH
 * {@code connect()} flow needs an Activity, and the binding keeps this service —
 * and the client's session callbacks — alive). Each started session is handed
 * over via {@link #ACTION_START}, which adds a monitor and promotes/refreshes
 * the foreground state. The service stays in the foreground while at least one
 * monitor is alive.
 *
 * <p>Uses the {@code specialUse} foreground service type: {@code dataSync} is
 * capped at 6 hours/day on Android 15, fatal for a monitoring app, and this app
 * is sideload-distributed so the Play declaration cost of specialUse is moot.
 */
public class MonitoringService extends android.app.Service implements SessionMonitor.Callback {

    public static final String TAG = "MonitoringService";

    public static final String ACTION_START = "com.sonelli.juicessh.performancemonitor.action.START_MONITORING";
    public static final String ACTION_DISCONNECT = "com.sonelli.juicessh.performancemonitor.action.DISCONNECT";

    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_SESSION_KEY = "session_key";
    public static final String EXTRA_CONNECTION_ID = "connection_id";
    public static final String EXTRA_CONNECTION_NAME = "connection_name";

    private static final int NUM_METRICS = MetricType.values().length;

    /** Ticks that error in a row before we assume a session is dead. */
    private static final int MAX_CONSECUTIVE_ERRORS = 5;

    /** Minimum ms between ongoing-notification updates (system throttles ~1/s). */
    private static final long NOTIFICATION_UPDATE_FLOOR_MS = 5000;

    /** Slowest cadence a hidden (non-active) server drops to, to spare battery/traffic. */
    private static final long BACKGROUND_FLOOR_MS = 20_000;

    private final IBinder binder = new LocalBinder();
    private final PluginClient client = new PluginClient();

    private final MutableLiveData<Boolean> clientStarted = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> monitoring = new MutableLiveData<>(false);
    private final MutableLiveData<MetricSnapshot> latestSnapshot = new MutableLiveData<>();
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();

    /** connectionId -> its live monitor. Insertion order kept for the notification headline. */
    private final Map<String, SessionMonitor> monitors = new LinkedHashMap<>();
    private final Set<Integer> usedSlots = new HashSet<>();
    /** Read from the engine looper (interval provider) as well as the main thread. */
    private volatile String activeConnectionId;

    private long lastNotificationUpdate = 0;

    /** Shared background looper for every server's poll loop + parsing (off the UI thread). */
    private HandlerThread pollThread;

    // Preference values cached so a poll tick doesn't re-read SharedPreferences per server.
    // Refreshed on a change listener. cachedUserIntervalMs is read from the engine looper too.
    private volatile long cachedUserIntervalMs = 2000;
    private List<AlertRule> cachedRules = Collections.emptyList();
    private long cachedSustainMs;
    private long cachedCooldownMs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;

    public class LocalBinder extends Binder {
        public MonitoringService getService() {
            return MonitoringService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        MonitoringNotification.ensureChannels(this);

        pollThread = new HandlerThread("pmon-poll");
        pollThread.start();

        refreshPrefsCache();
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        prefsListener = (prefs, key) -> refreshPrefsCache();
        sp.registerOnSharedPreferenceChangeListener(prefsListener);

        client.start(this, new OnClientStartedListener() {
            @Override
            public void onClientStarted() {
                clientStarted.setValue(true);
            }

            @Override
            public void onClientStopped() {
                clientStarted.setValue(false);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_START.equals(intent.getAction())) {
                startMonitoring(intent);
            } else if (ACTION_DISCONNECT.equals(intent.getAction())) {
                disconnectAll();
            }
        }
        // If the system kills us the session handles are gone and only the user can
        // reconnect (connect() needs an Activity), so restarting would be useless.
        return START_NOT_STICKY;
    }

    private void startMonitoring(Intent intent) {
        String connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID);
        String connectionName = intent.getStringExtra(EXTRA_CONNECTION_NAME);
        int sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1);
        String sessionKey = intent.getStringExtra(EXTRA_SESSION_KEY);
        if (connectionId == null || sessionId == -1 || sessionKey == null) {
            return;
        }

        // Reconnect of an already-monitored server: retire the old monitor first.
        SessionMonitor existing = monitors.remove(connectionId);
        if (existing != null) {
            existing.stop();
            usedSlots.remove(existing.slot);
        }

        int slot = allocateSlot();
        SessionMonitor monitor = new SessionMonitor(
                this, client, sessionId, sessionKey, connectionId, connectionName, slot, this,
                pollThread.getLooper());
        try {
            client.addSessionFinishedListener(sessionId, sessionKey, monitor);
        } catch (ServiceNotConnectedException e) {
            Log.e(TAG, "Could not register session finished listener");
        }
        monitor.start();
        monitors.put(connectionId, monitor);
        activeConnectionId = connectionId;

        // Enter (or refresh) the foreground state promptly after startForegroundService().
        int type = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
        ServiceCompat.startForeground(this, MonitoringNotification.MONITORING_ID, buildOngoingNotification(), type);
        lastNotificationUpdate = System.currentTimeMillis();

        publishActive();
    }

    // ---- SessionMonitor.Callback (arrives on the main looper) ----------------

    @Override
    public void onMonitorSnapshot(SessionMonitor monitor, MetricSnapshot snapshot) {
        evaluateAlerts(monitor, snapshot);
        if (monitor.connectionId.equals(activeConnectionId)) {
            latestSnapshot.setValue(snapshot);
            updateNotification(false);
        }
    }

    @Override
    public void onMonitorError(SessionMonitor monitor, String reason) {
        if (monitor.connectionId.equals(activeConnectionId)) {
            errorMessage.setValue(reason);
        }
        if (monitor.getConsecutiveErrors() >= MAX_CONSECUTIVE_ERRORS) {
            Log.w(TAG, "Ending monitoring of " + monitor.connectionName
                    + " after " + monitor.getConsecutiveErrors() + " consecutive errors");
            removeMonitor(monitor, true);
        }
    }

    @Override
    public void onMonitorSessionFinished(SessionMonitor monitor) {
        removeMonitor(monitor, true);
    }

    /**
     * Poll cadence for a monitor: the user interval for the active/visible server,
     * a slower floor for hidden servers. Called on the engine's background looper.
     */
    @Override
    public long getPollIntervalMs(SessionMonitor monitor) {
        long user = cachedUserIntervalMs;
        return monitor.connectionId.equals(activeConnectionId)
                ? user
                : Math.max(user, BACKGROUND_FLOOR_MS);
    }

    /** Reloads the cached preference values (interval, alert rules, timings). Runs on the main thread. */
    private void refreshPrefsCache() {
        PreferenceHelper prefs = new PreferenceHelper(this);
        cachedUserIntervalMs = prefs.getRefreshIntervalMs();
        cachedRules = prefs.getEnabledAlertRules();
        cachedSustainMs = prefs.getAlertSustainMs();
        cachedCooldownMs = prefs.getAlertCooldownMs();
    }

    private void evaluateAlerts(SessionMonitor monitor, MetricSnapshot snapshot) {
        List<AlertRule> rules = cachedRules;
        if (rules.isEmpty() || !canNotify()) {
            return;
        }
        List<AlertEvaluator.AlertEvent> events = monitor.getAlertEvaluator().evaluate(
                snapshot, rules, cachedSustainMs, cachedCooldownMs,
                System.currentTimeMillis());
        for (AlertEvaluator.AlertEvent event : events) {
            String metricName = getString(MetricLabels.titleRes(event.metric));
            String server = monitor.connectionName != null ? monitor.connectionName : getString(R.string.unknown);
            String text = getString(
                    event.fireWhenBelow ? R.string.alert_text_below : R.string.alert_text_above,
                    metricName,
                    formatAlertValue(event.metric, event.value),
                    formatAlertValue(event.metric, event.threshold),
                    server);
            // Per-monitor slot offset so two servers breaching the same metric don't collide.
            int id = MonitoringNotification.ALERT_ID_BASE + monitor.slot * NUM_METRICS + event.metric.id;
            NotificationManagerCompat.from(this).notify(
                    id, MonitoringNotification.buildAlert(this, getString(R.string.alert_title, metricName), text));
        }
    }

    /** Renders a threshold/value in the metric's stored unit for alert text. */
    private String formatAlertValue(MetricType type, double value) {
        switch (type) {
            case CPU:
            case DISK:
                return String.format(Locale.US, "%.0f%%", value);
            case RAM:
                return Format.formatKb((long) (value * 1024)); // stored as MB
            case TEMPERATURE:
                return Format.formatTemp(value, new PreferenceHelper(this).useFahrenheit());
            case LOAD:
                return String.format(Locale.US, "%.2f", value);
            default:
                return String.valueOf(value);
        }
    }

    private void removeMonitor(SessionMonitor monitor, boolean showEndedNotice) {
        if (monitors.remove(monitor.connectionId) == null) {
            return; // already removed
        }
        monitor.stop();
        if (showEndedNotice && canNotify()) {
            NotificationManagerCompat.from(this).notify(
                    MonitoringNotification.ENDED_ID_BASE + monitor.slot,
                    MonitoringNotification.buildEnded(this, monitor.connectionName));
        }
        usedSlots.remove(monitor.slot);

        if (monitor.connectionId.equals(activeConnectionId)) {
            // Leave it selected-but-disconnected so the UI offers Connect again.
            monitoring.setValue(false);
            latestSnapshot.setValue(null);
        }

        if (monitors.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            stopSelf();
        } else {
            updateNotification(true);
        }
    }

    private void updateNotification(boolean force) {
        if (monitors.isEmpty() || !canNotify()) {
            return;
        }
        long floor = Math.max(cachedUserIntervalMs, NOTIFICATION_UPDATE_FLOOR_MS);
        long now = System.currentTimeMillis();
        if (!force && now - lastNotificationUpdate < floor) {
            return;
        }
        lastNotificationUpdate = now;
        NotificationManagerCompat.from(this).notify(
                MonitoringNotification.MONITORING_ID, buildOngoingNotification());
    }

    /** Ongoing foreground notification listing every connected server and its metrics. */
    private Notification buildOngoingNotification() {
        List<String> lines = new ArrayList<>();
        for (SessionMonitor monitor : monitors.values()) {
            String name = monitor.connectionName != null ? monitor.connectionName : getString(R.string.unknown);
            lines.add(name + " — " + monitorSummary(monitor));
        }
        String title;
        String collapsed;
        if (monitors.size() == 1) {
            SessionMonitor only = monitors.values().iterator().next();
            title = only.connectionName != null ? only.connectionName : getString(R.string.app_name);
            collapsed = monitorSummary(only);
        } else {
            title = getString(R.string.monitoring_n_servers, monitors.size());
            collapsed = lines.isEmpty() ? getString(R.string.collecting_metrics) : lines.get(0);
        }
        return MonitoringNotification.buildMonitoring(this, title, collapsed, lines);
    }

    private String monitorSummary(SessionMonitor monitor) {
        return monitor.getLastSnapshot() != null
                ? MonitoringNotification.summaryText(this, monitor.getLastSnapshot())
                : getString(R.string.collecting_metrics);
    }

    private void publishActive() {
        SessionMonitor active = activeConnectionId != null ? monitors.get(activeConnectionId) : null;
        // Set monitoring before the snapshot: the Activity's tile renderer keys off
        // the monitoring flag, so it must be current when the snapshot observer runs.
        if (active != null) {
            monitoring.setValue(true);
            latestSnapshot.setValue(active.getLastSnapshot());
        } else {
            monitoring.setValue(false);
            latestSnapshot.setValue(null);
        }
    }

    private int allocateSlot() {
        int slot = 0;
        while (usedSlots.contains(slot)) {
            slot++;
        }
        usedSlots.add(slot);
        return slot;
    }

    private boolean canNotify() {
        return Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        for (SessionMonitor monitor : new ArrayList<>(monitors.values())) {
            monitor.stop();
        }
        monitors.clear();
        client.stop(this);
        if (prefsListener != null) {
            PreferenceManager.getDefaultSharedPreferences(this)
                    .unregisterOnSharedPreferenceChangeListener(prefsListener);
        }
        if (pollThread != null) {
            pollThread.quitSafely();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // ---- Binder API for MainActivity ----------------------------------------

    public PluginClient getPluginClient() {
        return client;
    }

    public void forwardActivityResult(int requestCode, int resultCode, Intent data) {
        client.gotActivityResult(requestCode, resultCode, data);
    }

    public LiveData<Boolean> getClientStarted() {
        return clientStarted;
    }

    /** Whether the currently active (shown) server is connected. */
    public LiveData<Boolean> getMonitoring() {
        return monitoring;
    }

    public LiveData<MetricSnapshot> getLatestSnapshot() {
        return latestSnapshot;
    }

    public LiveData<String> getErrorMessage() {
        return errorMessage;
    }

    /** Clears a shown error so LiveData stickiness can't re-toast it on rebind. */
    public void clearError() {
        errorMessage.setValue(null);
    }

    /** Switches which server the dashboard shows; re-publishes its cached state instantly. */
    public void setActive(String connectionId) {
        activeConnectionId = connectionId;
        lastNotificationUpdate = 0;
        publishActive();
        updateNotification(true);
        // Tighten the now-active server back to the fast cadence and refresh it at once,
        // instead of waiting out its slower background interval.
        SessionMonitor active = connectionId != null ? monitors.get(connectionId) : null;
        if (active != null) {
            active.pollNow();
        }
    }

    public boolean isConnected(String connectionId) {
        return connectionId != null && monitors.containsKey(connectionId);
    }

    public boolean isActiveMonitoring() {
        return activeConnectionId != null && monitors.containsKey(activeConnectionId);
    }

    /** Disconnects a single server; the others keep monitoring. */
    public void disconnect(String connectionId) {
        SessionMonitor monitor = monitors.get(connectionId);
        if (monitor == null) {
            return;
        }
        final int sid = monitor.sessionId;
        final String key = monitor.sessionKey;
        removeMonitor(monitor, false);
        new Thread(() -> {
            try {
                client.disconnect(sid, key);
            } catch (ServiceNotConnectedException e) {
                Log.e(TAG, "Failed to disconnect JuiceSSH session");
            }
        }).start();
    }

    public void disconnectAll() {
        for (SessionMonitor monitor : new ArrayList<>(monitors.values())) {
            disconnect(monitor.connectionId);
        }
    }

    /** Sparkline samples (oldest to newest) for one metric of the active server. */
    public float[] getHistory(MetricType type) {
        SessionMonitor active = activeConnectionId != null ? monitors.get(activeConnectionId) : null;
        return active != null ? active.getHistory(type) : new float[0];
    }

    /**
     * Runs the raw diagnostic transcript on the active server's session. Returns
     * false (and does not invoke the callback) if no server is currently active.
     */
    public boolean runActiveDiagnostics(SessionMonitor.DiagnosticListener cb) {
        SessionMonitor active = activeConnectionId != null ? monitors.get(activeConnectionId) : null;
        if (active == null) {
            return false;
        }
        active.runDiagnostics(cb);
        return true;
    }

    public String getConnectionId() {
        return activeConnectionId;
    }

    public String getConnectionName() {
        SessionMonitor active = activeConnectionId != null ? monitors.get(activeConnectionId) : null;
        return active != null ? active.connectionName : null;
    }
}
