package com.sonelli.juicessh.performancemonitor.activities;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.loader.app.LoaderManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.sonelli.juicessh.performancemonitor.R;
import com.sonelli.juicessh.performancemonitor.adapters.ConnectionSpinnerAdapter;
import com.sonelli.juicessh.performancemonitor.controllers.BaseController;
import com.sonelli.juicessh.performancemonitor.controllers.CpuUsageController;
import com.sonelli.juicessh.performancemonitor.controllers.DiskUsageController;
import com.sonelli.juicessh.performancemonitor.controllers.FreeRamController;
import com.sonelli.juicessh.performancemonitor.controllers.LoadAverageController;
import com.sonelli.juicessh.performancemonitor.controllers.NetworkUsageController;
import com.sonelli.juicessh.performancemonitor.controllers.TemperatureController;
import com.sonelli.juicessh.performancemonitor.helpers.InsetsUtils;
import com.sonelli.juicessh.performancemonitor.helpers.PreferenceHelper;
import com.sonelli.juicessh.performancemonitor.helpers.ThemeUtils;
import com.sonelli.juicessh.performancemonitor.loaders.ConnectionListLoader;
import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.views.MetricTileView;
import com.sonelli.juicessh.performancemonitor.views.SparklineView;
import com.sonelli.juicessh.pluginlibrary.PluginClient;
import com.sonelli.juicessh.pluginlibrary.PluginContract;
import com.sonelli.juicessh.pluginlibrary.exceptions.ServiceNotConnectedException;
import com.sonelli.juicessh.pluginlibrary.listeners.OnClientStartedListener;
import com.sonelli.juicessh.pluginlibrary.listeners.OnSessionFinishedListener;
import com.sonelli.juicessh.pluginlibrary.listeners.OnSessionStartedListener;

import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements OnSessionStartedListener, OnSessionFinishedListener {

    public static final String TAG = "MainActivity";

    private boolean isClientStarted = false;
    private final PluginClient client = new PluginClient();
    private final static int JUICESSH_REQUEST_CODE = 2585;

    private static final int REQUESTID_PERMISSIONS = 1388;
    private final static String PERMISSION_READ_CONNECTIONS = "com.sonelli.juicessh.api.v1.permission.READ_CONNECTIONS";
    private final static String PERMISSION_OPEN_SESSIONS = "com.sonelli.juicessh.api.v1.permission.OPEN_SESSIONS";

    private MaterialButton connectButton;
    private MaterialButton disconnectButton;
    private Spinner connectionSpinner;
    private ConnectionSpinnerAdapter spinnerAdapter;

    // Controllers
    private BaseController loadAverageController;
    private BaseController temperatureController;
    private BaseController freeRamController;
    private BaseController cpuUsageController;
    private BaseController diskUsageController;
    private BaseController networkUsageController;

    // Tiles
    private MetricTileView cpuTile;
    private MetricTileView ramTile;
    private MetricTileView temperatureTile;
    private MetricTileView loadTile;
    private MetricTileView networkTile;
    private MetricTileView diskTile;

    // State
    private volatile int sessionId;
    private volatile String sessionKey;
    private volatile boolean isConnected = false;

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

        this.cpuTile = findViewById(R.id.cpu_tile);
        this.ramTile = findViewById(R.id.ram_tile);
        this.temperatureTile = findViewById(R.id.temp_tile);
        this.loadTile = findViewById(R.id.load_tile);
        this.networkTile = findViewById(R.id.network_tile);
        this.diskTile = findViewById(R.id.disk_tile);

        for (MetricTileView tile : new MetricTileView[]{cpuTile, ramTile, temperatureTile, loadTile, networkTile, diskTile}) {
            tile.setOnClickListener(v -> showDetail((MetricTileView) v));
        }

        this.connectButton = findViewById(R.id.connect_button);
        this.connectButton.setOnClickListener(view -> {
            final UUID id = spinnerAdapter.getConnectionId(connectionSpinner.getSelectedItemPosition());
            if (id != null && isClientStarted) {
                connectButton.setText(R.string.connecting);
                connectButton.setEnabled(false);
                new Thread(() -> {
                    try {
                        client.connect(MainActivity.this, id, MainActivity.this, JUICESSH_REQUEST_CODE);
                    } catch (ServiceNotConnectedException e) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Could not connect to JuiceSSH Plugin Service", Toast.LENGTH_SHORT).show();
                            connectButton.setText(R.string.connect);
                            connectButton.setEnabled(true);
                        });
                    }
                }).start();
            }
        });

        this.disconnectButton = findViewById(R.id.disconnect_button);
        this.disconnectButton.setOnClickListener(view -> {
            if (sessionId > -1 && sessionKey != null && isClientStarted) {
                disconnectButton.setText(R.string.disconnecting);
                disconnectButton.setEnabled(false);
                new Thread(() -> {
                    try {
                        client.disconnect(sessionId, sessionKey);
                    } catch (ServiceNotConnectedException e) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Could not connect to JuiceSSH Plugin Service", Toast.LENGTH_SHORT).show());
                    }
                    disconnectButton.post(() -> {
                        disconnectButton.setEnabled(true);
                        disconnectButton.setText(R.string.disconnect);
                    });
                }).start();
            }
        });
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

                if (!isClientStarted) {
                    startPluginClient();
                }
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "JuiceSSH is not installed. Plugin Library will prompt user to install from the Play Store.");
        }

        updateButtons();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Stop the polling loops so they don't keep rescheduling (and leaking this
        // Activity via the tiles) after it's destroyed — onSessionFinished won't fire
        // here because client.stop() unbinds before the disconnect callback arrives.
        stopController(temperatureController);
        stopController(loadAverageController);
        stopController(freeRamController);
        stopController(cpuUsageController);
        stopController(diskUsageController);
        stopController(networkUsageController);

        if (isClientStarted) {
            if (isConnected) {
                try {
                    client.disconnect(sessionId, sessionKey);
                } catch (ServiceNotConnectedException e) {
                    Log.e(TAG, "Failed to disconnect JuiceSSH session used by performance monitor plugin");
                }
            }
            client.stop(this);
        }
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
        if (requestCode == JUICESSH_REQUEST_CODE) {
            client.gotActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onSessionStarted(final int sessionId, final String sessionKey) {
        this.sessionId = sessionId;
        this.sessionKey = sessionKey;
        this.isConnected = true;

        connectButton.setText(R.string.connect);
        updateButtons();

        try {
            client.addSessionFinishedListener(sessionId, sessionKey, this);
        } catch (ServiceNotConnectedException ignored) {
        }

        this.temperatureController = new TemperatureController(this)
                .setSessionId(sessionId).setSessionKey(sessionKey).setPluginClient(client)
                .setTile(temperatureTile).start();

        this.loadAverageController = new LoadAverageController(this)
                .setSessionId(sessionId).setSessionKey(sessionKey).setPluginClient(client)
                .setTile(loadTile).start();

        this.freeRamController = new FreeRamController(this)
                .setSessionId(sessionId).setSessionKey(sessionKey).setPluginClient(client)
                .setTile(ramTile).start();

        this.cpuUsageController = new CpuUsageController(this)
                .setSessionId(sessionId).setSessionKey(sessionKey).setPluginClient(client)
                .setTile(cpuTile).start();

        this.diskUsageController = new DiskUsageController(this)
                .setSessionId(sessionId).setSessionKey(sessionKey).setPluginClient(client)
                .setTile(diskTile).start();

        this.networkUsageController = new NetworkUsageController(this)
                .setSessionId(sessionId).setSessionKey(sessionKey).setPluginClient(client)
                .setTile(networkTile).start();
    }

    @Override
    public void onSessionCancelled() {
        // The user cancelled the JuiceSSH connection before it finished connecting.
        connectButton.setText(R.string.connect);
        updateButtons();
    }

    @Override
    public void onSessionFinished() {
        this.sessionId = -1;
        this.sessionKey = null;
        this.isConnected = false;

        stopController(temperatureController);
        stopController(loadAverageController);
        stopController(freeRamController);
        stopController(cpuUsageController);
        stopController(diskUsageController);
        stopController(networkUsageController);

        for (MetricTileView tile : new MetricTileView[]{cpuTile, ramTile, temperatureTile, loadTile, networkTile, diskTile}) {
            tile.reset();
        }

        connectButton.setText(R.string.connect);
        updateButtons();
    }

    private static void stopController(BaseController controller) {
        if (controller != null) {
            controller.stop();
        }
    }

    private void updateButtons() {
        connectButton.setVisibility(isConnected ? View.GONE : View.VISIBLE);
        connectButton.setEnabled(isClientStarted && !isConnected);
        disconnectButton.setVisibility(isConnected ? View.VISIBLE : View.GONE);
        disconnectButton.setEnabled(isConnected);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.settings) {
            startActivity(new Intent(this, SettingsActivity.class));
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
                if (!isClientStarted) {
                    startPluginClient();
                }

            } else if (Build.VERSION.SDK_INT < 23) {
                // Pre-M can't request permissions dynamically; start the client so it
                // can warn about plugin/JuiceSSH install order and guide a fix.
                startPluginClient();
            }
        }
    }

    public void startPluginClient() {
        client.start(this, new OnClientStartedListener() {
            @Override
            public void onClientStarted() {
                isClientStarted = true;
                connectButton.setText(R.string.connect);
                updateButtons();
            }

            @Override
            public void onClientStopped() {
                isClientStarted = false;
                updateButtons();
            }
        });
    }

    /** Opens a bottom sheet with the tile's larger sparkline and per-metric breakdown. */
    private void showDetail(MetricTileView tile) {
        View content = getLayoutInflater().inflate(R.layout.bottomsheet_detail, null);

        TextView title = content.findViewById(R.id.detail_title);
        SparklineView sparkline = content.findViewById(R.id.detail_sparkline);
        LinearLayout rowsContainer = content.findViewById(R.id.detail_rows);

        title.setText(tile.getTitle());
        sparkline.setColor(tile.getAccentColor());
        sparkline.setData(tile.getHistory());

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

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(content);
        dialog.show();
    }
}
