package com.sonelli.juicessh.performancemonitor.activities;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.loader.app.LoaderManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.sonelli.juicessh.performancemonitor.R;
import com.sonelli.juicessh.performancemonitor.adapters.ConnectionSpinnerAdapter;
import com.sonelli.juicessh.performancemonitor.helpers.InsetsUtils;
import com.sonelli.juicessh.performancemonitor.helpers.PreferenceHelper;
import com.sonelli.juicessh.performancemonitor.helpers.ThemeUtils;
import com.sonelli.juicessh.performancemonitor.loaders.ConnectionListLoader;
import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;
import com.sonelli.juicessh.performancemonitor.model.MetricSnapshot;
import com.sonelli.juicessh.performancemonitor.model.MetricType;
import com.sonelli.juicessh.performancemonitor.service.MonitoringService;
import com.sonelli.juicessh.performancemonitor.service.SessionMonitor;
import com.sonelli.juicessh.performancemonitor.views.MetricTileView;
import com.sonelli.juicessh.performancemonitor.views.SparklineView;
import com.sonelli.juicessh.pluginlibrary.PluginClient;
import com.sonelli.juicessh.pluginlibrary.PluginContract;
import com.sonelli.juicessh.pluginlibrary.exceptions.ServiceNotConnectedException;
import com.sonelli.juicessh.pluginlibrary.listeners.OnSessionStartedListener;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dashboard UI. All polling lives in {@link MonitoringService}; this Activity
 * binds to it for its whole lifetime (the binding must survive the JuiceSSH
 * connect excursion so the plugin client's callbacks stay alive), renders its
 * LiveData into the metric tiles, and hands newly started sessions over to it.
 */
public class MainActivity extends AppCompatActivity implements OnSessionStartedListener {

    public static final String TAG = "MainActivity";

    private final static int JUICESSH_REQUEST_CODE = 2585;

    private static final int REQUESTID_PERMISSIONS = 1388;
    private static final int REQUESTID_NOTIFICATIONS = 1389;
    private final static String PERMISSION_READ_CONNECTIONS = "com.sonelli.juicessh.api.v1.permission.READ_CONNECTIONS";
    private final static String PERMISSION_OPEN_SESSIONS = "com.sonelli.juicessh.api.v1.permission.OPEN_SESSIONS";

    private MaterialButton connectButton;
    private MaterialButton disconnectButton;
    private MaterialButton rawOutputButton;
    private View connectedActions;
    private Spinner connectionSpinner;
    private ConnectionSpinnerAdapter spinnerAdapter;

    // Tiles
    private MetricTileView cpuTile;
    private MetricTileView ramTile;
    private MetricTileView temperatureTile;
    private MetricTileView loadTile;
    private MetricTileView networkTile;
    private MetricTileView diskTile;
    private final EnumMap<MetricType, MetricTileView> tileMap = new EnumMap<>(MetricType.class);

    // Monitoring service binding + state mirrored from its LiveData
    private MonitoringService service;
    private boolean isClientStarted = false;
    /** Whether the currently *shown* (selected) server is connected. */
    private boolean isMonitoring = false;
    /** True while a JuiceSSH connect is in flight (guards against overlapping connects). */
    private boolean connecting = false;
    /** Set when the user physically interacts with the spinner, so we ignore programmatic selections. */
    private boolean spinnerTouched = false;
    private String selectedConnectionId;
    private String selectedConnectionName;
    private String pendingConnectionId;
    private String pendingConnectionName;

    private final Observer<Boolean> clientStartedObserver = started -> {
        isClientStarted = Boolean.TRUE.equals(started);
        updateButtons();
    };

    private final Observer<Boolean> monitoringObserver = value -> {
        boolean nowMonitoring = Boolean.TRUE.equals(value);
        if (isMonitoring && !nowMonitoring) {
            // The shown server disconnected (user action, remote close, errors) or we
            // switched to a not-yet-connected server: clear its stale tiles.
            for (MetricTileView tile : tileMap.values()) {
                tile.reset();
            }
            connectButton.setText(R.string.connect);
            disconnectButton.setText(R.string.disconnect);
            rawOutputButton.setText(R.string.raw_output);
        }
        isMonitoring = nowMonitoring;
        updateButtons();
    };

    private final Observer<MetricSnapshot> snapshotObserver = this::applySnapshot;

    private final Observer<String> errorObserver = reason -> {
        if (reason != null && isMonitoring) {
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show();
            if (service != null) {
                service.clearError();
            }
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MonitoringService.LocalBinder) binder).getService();
            service.getClientStarted().observe(MainActivity.this, clientStartedObserver);
            service.getMonitoring().observe(MainActivity.this, monitoringObserver);
            service.getLatestSnapshot().observe(MainActivity.this, snapshotObserver);
            service.getErrorMessage().observe(MainActivity.this, errorObserver);
            seedTilesFromService();
            updateButtons();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            isClientStarted = false;
            updateButtons();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtils.apply(new PreferenceHelper(this).getThemeMode());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        InsetsUtils.applySystemBars(this);

        if (new PreferenceHelper(this).getKeepScreenOnFlag()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // Only SSH connections can run the commands used to poll metrics.
        this.spinnerAdapter = new ConnectionSpinnerAdapter(this, PluginContract.Connections.TYPE_SSH);
        this.connectionSpinner = findViewById(R.id.connection_spinner);
        this.connectionSpinner.setAdapter(spinnerAdapter);
        // Distinguish a real user pick from the programmatic selections Android fires
        // on data load / rotation, so we don't auto-connect on those.
        this.connectionSpinner.setOnTouchListener((v, event) -> {
            spinnerTouched = true;
            return false;
        });
        this.connectionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (spinnerTouched) {
                    spinnerTouched = false;
                    onServerSelected(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        this.cpuTile = findViewById(R.id.cpu_tile);
        this.ramTile = findViewById(R.id.ram_tile);
        this.temperatureTile = findViewById(R.id.temp_tile);
        this.loadTile = findViewById(R.id.load_tile);
        this.networkTile = findViewById(R.id.network_tile);
        this.diskTile = findViewById(R.id.disk_tile);

        tileMap.put(MetricType.CPU, cpuTile);
        tileMap.put(MetricType.RAM, ramTile);
        tileMap.put(MetricType.TEMPERATURE, temperatureTile);
        tileMap.put(MetricType.LOAD, loadTile);
        tileMap.put(MetricType.NETWORK, networkTile);
        tileMap.put(MetricType.DISK, diskTile);

        for (Map.Entry<MetricType, MetricTileView> entry : tileMap.entrySet()) {
            MetricType type = entry.getKey();
            entry.getValue().setOnClickListener(v -> showDetail(type, (MetricTileView) v));
        }

        this.connectButton = findViewById(R.id.connect_button);
        this.connectButton.setOnClickListener(view -> {
            int position = connectionSpinner.getSelectedItemPosition();
            final UUID id = spinnerAdapter.getConnectionId(position);
            if (id != null) {
                selectedConnectionId = id.toString();
                selectedConnectionName = spinnerAdapter.getConnectionName(position);
                beginConnect(id, selectedConnectionName);
            }
        });

        this.connectedActions = findViewById(R.id.connected_actions);

        this.disconnectButton = findViewById(R.id.disconnect_button);
        this.disconnectButton.setOnClickListener(view -> {
            if (service != null && selectedConnectionId != null && service.isConnected(selectedConnectionId)) {
                disconnectButton.setText(R.string.disconnecting);
                disconnectButton.setEnabled(false);
                service.disconnect(selectedConnectionId);
            }
        });

        this.rawOutputButton = findViewById(R.id.raw_output_button);
        this.rawOutputButton.setOnClickListener(view -> collectRawOutput());

        // Keep the binding for the Activity's whole lifetime — see class javadoc.
        bindService(new Intent(this, MonitoringService.class), serviceConnection, Context.BIND_AUTO_CREATE);

        requestNotificationPermissionIfNeeded();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUESTID_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Reflect any keep-screen-on change made in Settings.
        if (new PreferenceHelper(this).getKeepScreenOnFlag()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        try {
            if (ContextCompat.checkSelfPermission(this, PERMISSION_READ_CONNECTIONS) != PackageManager.PERMISSION_GRANTED) {

                if (ActivityCompat.shouldShowRequestPermissionRationale(this, PERMISSION_READ_CONNECTIONS)) {
                    Toast.makeText(this, R.string.plugin_permissions_request, Toast.LENGTH_LONG).show();
                }

                ActivityCompat.requestPermissions(this, new String[]{PERMISSION_READ_CONNECTIONS, PERMISSION_OPEN_SESSIONS}, REQUESTID_PERMISSIONS);

            } else {
                // Load the connection list off the UI thread via the JuiceSSH content provider.
                LoaderManager.getInstance(this).initLoader(0, null, new ConnectionListLoader(this, spinnerAdapter));
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "JuiceSSH is not installed. Plugin Library will prompt user to install from the Play Store.");
        }

        updateButtons();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // The service keeps running (and in the foreground) while monitoring;
        // disconnecting is an explicit user action now, not an Activity event.
        unbindService(serviceConnection);
        service = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        menu.findItem(R.id.keep_screen_on).setChecked(new PreferenceHelper(this).getKeepScreenOnFlag());
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Required so the plugin can interact with sessions it starts.
        if (requestCode == JUICESSH_REQUEST_CODE && service != null) {
            service.forwardActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * The user picked a server in the dropdown: switch the dashboard to it. If it's
     * already being monitored the switch is instant; otherwise auto-connect while
     * every other connected server keeps running in the background.
     */
    private void onServerSelected(int position) {
        final UUID id = spinnerAdapter.getConnectionId(position);
        if (id == null || service == null) {
            return;
        }
        selectedConnectionId = id.toString();
        selectedConnectionName = spinnerAdapter.getConnectionName(position);
        service.setActive(selectedConnectionId);
        if (service.isConnected(selectedConnectionId)) {
            seedTilesFromService();
        } else {
            beginConnect(id, selectedConnectionName);
        }
    }

    /** Opens a JuiceSSH session for a server, unless one is already being opened. */
    private void beginConnect(final UUID id, String name) {
        if (connecting || !isClientStarted || service == null) {
            return;
        }
        connecting = true;
        pendingConnectionId = id.toString();
        pendingConnectionName = name;
        connectButton.setText(R.string.connecting);
        updateButtons();
        final PluginClient client = service.getPluginClient();
        new Thread(() -> {
            try {
                client.connect(MainActivity.this, id, MainActivity.this, JUICESSH_REQUEST_CODE);
            } catch (ServiceNotConnectedException e) {
                runOnUiThread(() -> {
                    connecting = false;
                    Toast.makeText(MainActivity.this, "Could not connect to JuiceSSH Plugin Service", Toast.LENGTH_SHORT).show();
                    connectButton.setText(R.string.connect);
                    updateButtons();
                });
            }
        }).start();
    }

    @Override
    public void onSessionStarted(final int sessionId, final String sessionKey) {
        connecting = false;
        connectButton.setText(R.string.connect);

        // Hand the session to the foreground service, which adds it as a monitor
        // (keeping any others alive) and makes it the active/shown server.
        Intent intent = new Intent(this, MonitoringService.class)
                .setAction(MonitoringService.ACTION_START)
                .putExtra(MonitoringService.EXTRA_SESSION_ID, sessionId)
                .putExtra(MonitoringService.EXTRA_SESSION_KEY, sessionKey)
                .putExtra(MonitoringService.EXTRA_CONNECTION_ID, pendingConnectionId)
                .putExtra(MonitoringService.EXTRA_CONNECTION_NAME, pendingConnectionName);
        ContextCompat.startForegroundService(this, intent);

        updateButtons();
    }

    @Override
    public void onSessionCancelled() {
        // The user cancelled the JuiceSSH connection before it finished connecting.
        connecting = false;
        connectButton.setText(R.string.connect);
        updateButtons();
    }

    /** Renders one snapshot from the service into the tiles. */
    private void applySnapshot(MetricSnapshot snapshot) {
        if (snapshot == null || service == null || !isMonitoring) {
            return;
        }
        for (Map.Entry<MetricType, MetricTileView> entry : tileMap.entrySet()) {
            MetricReading reading = snapshot.get(entry.getKey());
            if (reading == null) {
                continue; // nothing this tick (e.g. delta metric warming up)
            }
            MetricTileView tile = entry.getValue();
            if (reading.unavailable) {
                tile.setValue(getString(R.string.not_available));
                // Surface the reason (e.g. "Not available on Windows") in the detail sheet.
                tile.setDetailRows(reading.detailRows);
                continue;
            }
            if (reading.display != null) {
                tile.setValue(reading.display);
            }
            if (reading.hasValue()) {
                // The service owns the history; mirror it so sparklines survive
                // Activity recreation without double-appending samples.
                tile.setHistory(service.getHistory(entry.getKey()));
            }
            if (!reading.detailRows.isEmpty()) {
                tile.setDetailRows(reading.detailRows);
            }
        }
    }

    /** Re-seeds tiles from the active server's cached history (rebind / instant switch). */
    private void seedTilesFromService() {
        if (service == null || !service.isActiveMonitoring()) {
            return;
        }
        for (Map.Entry<MetricType, MetricTileView> entry : tileMap.entrySet()) {
            entry.getValue().setHistory(service.getHistory(entry.getKey()));
        }
        // Values and detail rows arrive via the sticky latestSnapshot LiveData.
    }

    private void updateButtons() {
        connectButton.setVisibility(isMonitoring ? View.GONE : View.VISIBLE);
        connectButton.setEnabled(isClientStarted && !isMonitoring && !connecting);
        connectedActions.setVisibility(isMonitoring ? View.VISIBLE : View.GONE);
        disconnectButton.setEnabled(isMonitoring && !connecting);
        rawOutputButton.setEnabled(isMonitoring && !connecting);
        connectionSpinner.setEnabled(!connecting);
    }

    /**
     * Runs the raw diagnostic transcript on the active server and shows it. This
     * is the debugging escape hatch for "everything reads N/A": it reveals the
     * shell's identity and exactly what each poll command returns on the server.
     */
    private void collectRawOutput() {
        if (service == null) {
            return;
        }
        rawOutputButton.setEnabled(false);
        rawOutputButton.setText(R.string.raw_output_collecting);
        // Delivered on the main thread by SessionMonitor.
        boolean started = service.runActiveDiagnostics(new SessionMonitor.DiagnosticListener() {
            @Override
            public void onResult(String transcript) {
                rawOutputButton.setText(R.string.raw_output);
                updateButtons();
                showRawOutput(transcript);
            }

            @Override
            public void onError(String reason) {
                rawOutputButton.setText(R.string.raw_output);
                updateButtons();
                Toast.makeText(MainActivity.this, reason, Toast.LENGTH_SHORT).show();
            }
        });
        if (!started) {
            rawOutputButton.setText(R.string.raw_output);
            updateButtons();
            Toast.makeText(this, R.string.raw_output_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    /** Shows the diagnostic transcript in a scrollable, selectable dialog with copy/share. */
    private void showRawOutput(String transcript) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        View content = getLayoutInflater().inflate(R.layout.dialog_raw_output, null);
        ((TextView) content.findViewById(R.id.raw_output_text)).setText(transcript);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.raw_output_title)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.copy, (dialog, which) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText(
                                getString(R.string.raw_output_title), transcript));
                        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.share, (dialog, which) -> {
                    Intent share = new Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, transcript);
                    startActivity(Intent.createChooser(share, getString(R.string.share)));
                })
                .show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.disconnect_all) {
            if (service != null) {
                service.disconnectAll();
            }
            return true;
        } else if (id == R.id.about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        } else if (id == R.id.fork_on_github) {
            Intent urlIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.app_url)));
            urlIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
            try {
                startActivity(Intent.createChooser(urlIntent, getString(R.string.open_address)));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (id == R.id.keep_screen_on) {
            item.setChecked(!item.isChecked());
            new PreferenceHelper(this).setKeepScreenOnFlag(item.isChecked());
            if (item.isChecked()) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUESTID_PERMISSIONS) {
            if (grantResults.length > 1
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                LoaderManager.getInstance(this).initLoader(0, null, new ConnectionListLoader(this, spinnerAdapter));
            }
        }
        // REQUESTID_NOTIFICATIONS needs no handling: monitoring works without the
        // permission, the user just won't see the status notification.
    }

    /** Opens a bottom sheet with the tile's larger sparkline and per-metric breakdown. */
    private void showDetail(MetricType metric, MetricTileView tile) {
        View content = getLayoutInflater().inflate(R.layout.bottomsheet_detail, null);

        TextView title = content.findViewById(R.id.detail_title);
        SparklineView sparkline = content.findViewById(R.id.detail_sparkline);
        LinearLayout rowsContainer = content.findViewById(R.id.detail_rows);

        title.setText(tile.getTitle());
        sparkline.setColor(tile.getAccentColor());
        sparkline.setData(tile.getHistory());

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        content.findViewById(R.id.detail_view_history).setOnClickListener(v -> {
            dialog.dismiss();
            openHistory(metric);
        });

        List<DetailRow> rows = tile.getDetailRows();
        if (rows.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_detail);
            rowsContainer.addView(empty);
        } else {
            for (DetailRow row : rows) {
                View rowView = getLayoutInflater().inflate(R.layout.list_detail_row, rowsContainer, false);
                ((TextView) rowView.findViewById(R.id.row_label)).setText(row.label);
                ((TextView) rowView.findViewById(R.id.row_value)).setText(row.value);
                LinearProgressIndicator bar = rowView.findViewById(R.id.row_bar);
                if (row.hasBar()) {
                    int pct = (int) Math.round(Math.max(0, Math.min(1, row.fraction)) * 100);
                    bar.setVisibility(View.VISIBLE);
                    bar.setIndicatorColor(tile.getAccentColor());
                    bar.setProgressCompat(pct, false);
                }
                rowsContainer.addView(rowView);
            }
        }

        dialog.setContentView(content);
        dialog.show();
    }

    /** Launches the full history/chart screen for a metric of the current server. */
    private void openHistory(MetricType metric) {
        String connectionId = service != null ? service.getConnectionId() : null;
        String connectionName = service != null ? service.getConnectionName() : null;
        Intent intent = new Intent(this, MetricDetailActivity.class)
                .putExtra(MetricDetailActivity.EXTRA_METRIC, metric.id)
                .putExtra(MetricDetailActivity.EXTRA_CONNECTION_ID, connectionId)
                .putExtra(MetricDetailActivity.EXTRA_CONNECTION_NAME, connectionName);
        startActivity(intent);
    }
}
