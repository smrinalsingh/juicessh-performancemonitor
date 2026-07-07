package com.sonelli.juicessh.performancemonitor.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.sonelli.juicessh.performancemonitor.data.HistoryRecorder;
import com.sonelli.juicessh.performancemonitor.model.MetricHistory;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;
import com.sonelli.juicessh.performancemonitor.model.MetricSnapshot;
import com.sonelli.juicessh.performancemonitor.model.MetricType;
import com.sonelli.juicessh.pluginlibrary.PluginClient;
import com.sonelli.juicessh.pluginlibrary.exceptions.ServiceNotConnectedException;
import com.sonelli.juicessh.pluginlibrary.listeners.OnSessionExecuteListener;
import com.sonelli.juicessh.pluginlibrary.listeners.OnSessionFinishedListener;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * All state for one monitored server: its JuiceSSH session, poll engine,
 * sparkline histories, alert evaluator, history recorder, and last snapshot.
 * {@link MonitoringService} holds one of these per connected server so several
 * run concurrently; the dashboard shows whichever is active.
 *
 * <p>Implements the engine's {@link MonitoringEngine.Listener} and the plugin's
 * {@link OnSessionFinishedListener}, tagging every callback with its own
 * identity via {@link Callback} so the service knows which server it came from
 * (the raw plugin/engine callbacks carry no session identity).
 */
public class SessionMonitor implements MonitoringEngine.Listener, OnSessionFinishedListener {

    public static final String TAG = "SessionMonitor";

    /** Sparkline window, matching the old per-tile ring buffers. */
    private static final int HISTORY_CAPACITY = 60;

    /**
     * One-shot OS probe covering all three shells a server might answer SSH with:
     * {@code $OSTYPE} is set by bash-family shells (linux-gnu, darwin…);
     * {@code $env:OS} is "Windows_NT" in PowerShell; and {@code %OS%} is
     * "Windows_NT" in cmd.exe (the default Windows OpenSSH shell). cmd.exe treats
     * the whole line as one {@code echo} and expands only {@code %OS%}, so its
     * reply still carries {@code CMDOS=Windows_NT}; bash/PowerShell leave
     * {@code %OS%} literal, so it never false-positives them.
     */
    private static final String OS_PROBE =
            "echo \"OSTYPE=$OSTYPE\"; echo \"WINOS=$env:OS\"; echo \"CMDOS=%OS%\"";

    public interface Callback {
        void onMonitorSnapshot(SessionMonitor monitor, MetricSnapshot snapshot);

        void onMonitorError(SessionMonitor monitor, String reason);

        void onMonitorSessionFinished(SessionMonitor monitor);
    }

    public final int sessionId;
    public final String sessionKey;
    public final String connectionId;
    public final String connectionName;
    /** Stable per-monitor index reserved for its notification ids while it lives. */
    public final int slot;

    private final Context context;
    private final PluginClient client;
    private final EnumMap<MetricType, MetricHistory> histories = new EnumMap<>(MetricType.class);
    private final AlertEvaluator alertEvaluator = new AlertEvaluator();
    private final HistoryRecorder historyRecorder;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());

    private MonitoringEngine engine;
    private CommandProfile detectedProfile;
    private MetricSnapshot lastSnapshot;
    private int consecutiveErrors;
    private boolean stopped;

    public SessionMonitor(Context context, PluginClient client, int sessionId, String sessionKey,
                          String connectionId, String connectionName, int slot, Callback callback) {
        this.context = context.getApplicationContext();
        this.client = client;
        this.sessionId = sessionId;
        this.sessionKey = sessionKey;
        this.connectionId = connectionId;
        this.connectionName = connectionName;
        this.slot = slot;
        this.callback = callback;
        this.historyRecorder = new HistoryRecorder(context, connectionId);
        for (MetricType type : MetricType.values()) {
            histories.put(type, new MetricHistory(HISTORY_CAPACITY));
        }
    }

    /** Detects the server OS, then starts polling with the matching command profile. */
    public void start() {
        detectOsThenStart();
    }

    private void detectOsThenStart() {
        final List<String> lines = new ArrayList<>();
        try {
            client.executeCommandOnSession(sessionId, sessionKey, OS_PROBE, new OnSessionExecuteListener() {
                @Override
                public void onOutputLine(String line) {
                    lines.add(line);
                }

                @Override
                public void onCompleted(int exitCode) {
                    main.post(() -> startEngine(detectProfile(lines)));
                }

                @Override
                public void onError(int error, String reason) {
                    main.post(() -> startEngine(new LinuxCommandProfile())); // default to Linux
                }
            });
        } catch (ServiceNotConnectedException e) {
            Log.d(TAG, "Could not run OS probe; defaulting to Linux profile");
            startEngine(new LinuxCommandProfile());
        }
    }

    private void startEngine(CommandProfile profile) {
        if (stopped) {
            return; // disconnected during OS detection
        }
        detectedProfile = profile;
        engine = new MonitoringEngine(context, client, sessionId, sessionKey, connectionId, profile, this);
        engine.start();
    }

    /**
     * bash-family sets $OSTYPE; PowerShell resolves $env:OS to "Windows_NT"; cmd.exe
     * resolves %OS% to "Windows_NT". A real $OSTYPE value still wins for Linux, so
     * a Windows box that happens to have OSTYPE exported isn't misread.
     */
    private static CommandProfile detectProfile(List<String> lines) {
        boolean unix = false;
        boolean windows = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("OSTYPE=")) {
                String value = line.substring("OSTYPE=".length());
                if (!value.isEmpty() && !value.equals("$OSTYPE")) {
                    unix = true;
                }
            }
            if (line.contains("WINOS=Windows_NT") || line.contains("CMDOS=Windows_NT")) {
                windows = true;
            }
        }
        return (windows && !unix) ? new WindowsCommandProfile() : new LinuxCommandProfile();
    }

    public void stop() {
        stopped = true;
        if (engine != null) {
            engine.stop();
            engine = null;
        }
        historyRecorder.shutdown();
    }

    public MetricSnapshot getLastSnapshot() {
        return lastSnapshot;
    }

    public AlertEvaluator getAlertEvaluator() {
        return alertEvaluator;
    }

    public int getConsecutiveErrors() {
        return consecutiveErrors;
    }

    public float[] getHistory(MetricType type) {
        MetricHistory history = histories.get(type);
        return history != null ? history.toArray() : new float[0];
    }

    // ---- Diagnostics ---------------------------------------------------------

    /** Delivers the verbatim diagnostic transcript on the main thread. */
    public interface DiagnosticListener {
        void onResult(String transcript);

        void onError(String reason);
    }

    /**
     * Runs the OS probe and both profiles' poll commands on this session and
     * hands back their raw output as one transcript. Backs the dashboard's
     * "Raw output" button: it shows the shell's identity and exactly what each
     * command returns, independent of whether OS detection picked the right
     * profile — the fastest way to see why metrics read N/A.
     */
    public void runDiagnostics(DiagnosticListener cb) {
        String detected = detectedProfile != null
                ? detectedProfile.getClass().getSimpleName() : "(not yet detected)";

        List<String[]> steps = new ArrayList<>();
        steps.add(new String[]{"OS PROBE", OS_PROBE});
        steps.add(new String[]{"LINUX COMMAND", new LinuxCommandProfile().command()});
        steps.add(new String[]{"WINDOWS COMMAND", new WindowsCommandProfile().command()});

        StringBuilder transcript = new StringBuilder();
        transcript.append("Connection: ").append(connectionName).append('\n');
        transcript.append("Detected profile: ").append(detected).append('\n');

        runDiagnosticStep(steps, 0, transcript, cb);
    }

    /** Runs one diagnostic step, then chains the next from its callback (never overlapping). */
    private void runDiagnosticStep(List<String[]> steps, int index, StringBuilder transcript, DiagnosticListener cb) {
        if (index >= steps.size()) {
            String result = transcript.toString();
            main.post(() -> cb.onResult(result));
            return;
        }
        String label = steps.get(index)[0];
        String command = steps.get(index)[1];
        transcript.append("\n===== ").append(label).append(" =====\n");
        transcript.append("$ ").append(command).append('\n');

        final List<String> lines = new ArrayList<>();
        try {
            client.executeCommandOnSession(sessionId, sessionKey, command, new OnSessionExecuteListener() {
                @Override
                public void onOutputLine(String line) {
                    lines.add(line);
                }

                @Override
                public void onCompleted(int exitCode) {
                    for (String l : lines) {
                        transcript.append(l).append('\n');
                    }
                    if (lines.isEmpty()) {
                        transcript.append("(no output)\n");
                    }
                    transcript.append("[exit code ").append(exitCode).append("]\n");
                    runDiagnosticStep(steps, index + 1, transcript, cb);
                }

                @Override
                public void onError(int error, String reason) {
                    transcript.append("[error ").append(error).append(": ").append(reason).append("]\n");
                    runDiagnosticStep(steps, index + 1, transcript, cb);
                }
            });
        } catch (ServiceNotConnectedException e) {
            main.post(() -> cb.onError("Could not run diagnostics: JuiceSSH plugin service not connected"));
        }
    }

    // ---- MonitoringEngine.Listener (arrives on the main looper) --------------

    @Override
    public void onSnapshot(MetricSnapshot snapshot) {
        consecutiveErrors = 0;
        for (Map.Entry<MetricType, MetricReading> entry : snapshot.readings.entrySet()) {
            MetricReading reading = entry.getValue();
            if (reading.hasValue()) {
                MetricHistory history = histories.get(entry.getKey());
                if (history != null) {
                    history.add((float) reading.value);
                }
            }
        }
        lastSnapshot = snapshot;
        historyRecorder.record(snapshot);
        callback.onMonitorSnapshot(this, snapshot);
    }

    @Override
    public void onEngineError(String reason) {
        consecutiveErrors++;
        callback.onMonitorError(this, reason);
    }

    // ---- OnSessionFinishedListener ------------------------------------------

    @Override
    public void onSessionFinished() {
        callback.onMonitorSessionFinished(this);
    }
}
