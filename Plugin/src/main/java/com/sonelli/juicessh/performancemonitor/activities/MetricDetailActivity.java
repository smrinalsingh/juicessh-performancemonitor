package com.sonelli.juicessh.performancemonitor.activities;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.sonelli.juicessh.performancemonitor.R;
import com.sonelli.juicessh.performancemonitor.data.AppDatabase;
import com.sonelli.juicessh.performancemonitor.data.ChartPoint;
import com.sonelli.juicessh.performancemonitor.data.MetricSampleDao;
import com.sonelli.juicessh.performancemonitor.helpers.CsvExporter;
import com.sonelli.juicessh.performancemonitor.helpers.Format;
import com.sonelli.juicessh.performancemonitor.helpers.MetricLabels;
import com.sonelli.juicessh.performancemonitor.helpers.PreferenceHelper;
import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;
import com.sonelli.juicessh.performancemonitor.model.MetricSnapshot;
import com.sonelli.juicessh.performancemonitor.model.MetricType;
import com.sonelli.juicessh.performancemonitor.service.MonitoringService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen history for a single metric: a time-axis line chart backed by
 * Room, with selectable ranges, plus the live per-metric breakdown when this is
 * the currently monitored connection. A separate Activity (not the bottom
 * sheet) so the chart's pan/zoom gestures don't fight sheet drag-to-dismiss.
 */
public class MetricDetailActivity extends AppCompatActivity {

    public static final String EXTRA_CONNECTION_ID = "connection_id";
    public static final String EXTRA_METRIC = "metric";
    public static final String EXTRA_CONNECTION_NAME = "connection_name";

    private enum Range {
        R15M(15 * 60_000L, 10_000L),
        R1H(60 * 60_000L, 30_000L),
        R6H(6 * 60 * 60_000L, 120_000L),
        R24H(24 * 60 * 60_000L, 480_000L);

        final long spanMs;
        final long bucketMs;

        Range(long spanMs, long bucketMs) {
            this.spanMs = spanMs;
            this.bucketMs = bucketMs;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private String connectionId;
    private String connectionName;
    private MetricType metric;
    private Range range = Range.R1H;

    private LineChart chart;
    private TextView emptyView;
    private LinearLayout rowsContainer;
    private int accentColor;

    private MonitoringService service;
    private boolean serviceBound;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MonitoringService.LocalBinder) binder).getService();
            service.getLatestSnapshot().observe(MetricDetailActivity.this, MetricDetailActivity.this::renderLiveRows);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_metric_detail);

        connectionId = getIntent().getStringExtra(EXTRA_CONNECTION_ID);
        connectionName = getIntent().getStringExtra(EXTRA_CONNECTION_NAME);
        metric = MetricType.fromId(getIntent().getIntExtra(EXTRA_METRIC, -1));
        if (metric == null) {
            finish();
            return;
        }
        accentColor = ContextCompat.getColor(this, MetricLabels.accentColorRes(metric));

        MaterialToolbar toolbar = findViewById(R.id.detail_toolbar);
        toolbar.setTitle(MetricLabels.titleRes(metric));
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        chart = findViewById(R.id.detail_chart);
        emptyView = findViewById(R.id.detail_empty);
        rowsContainer = findViewById(R.id.detail_rows);
        configureChart();

        MaterialButtonToggleGroup toggle = findViewById(R.id.range_toggle);
        toggle.check(R.id.range_1h);
        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            if (checkedId == R.id.range_15m) {
                range = Range.R15M;
            } else if (checkedId == R.id.range_1h) {
                range = Range.R1H;
            } else if (checkedId == R.id.range_6h) {
                range = Range.R6H;
            } else if (checkedId == R.id.range_24h) {
                range = Range.R24H;
            }
            loadChart();
        });

        bindService(new Intent(this, MonitoringService.class), serviceConnection, Context.BIND_AUTO_CREATE);
        serviceBound = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadChart();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        executor.shutdown();
    }

    private void configureChart() {
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setDrawGridBackground(false);
        chart.setNoDataText(getString(R.string.no_history_yet));

        int labelColor = resolveTextColor();
        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setTextColor(labelColor);
        x.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                // x is seconds since the range start; map back to wall-clock.
                long ts = rangeStartMs + (long) (value * 1000L);
                return timeFormat.format(new Date(ts));
            }
        });

        YAxis left = chart.getAxisLeft();
        left.setTextColor(labelColor);
        left.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return formatYValue(value);
            }
        });
        chart.getAxisRight().setEnabled(false);
    }

    private long rangeStartMs;

    private void loadChart() {
        final long now = System.currentTimeMillis();
        rangeStartMs = now - range.spanMs;
        final Range r = range;
        executor.execute(() -> {
            MetricSampleDao dao = AppDatabase.getInstance(this).metricSampleDao();
            final List<ChartPoint> points = dao.getBucketed(connectionId, metric.id, rangeStartMs, r.bucketMs);
            runOnUiThread(() -> showChart(points));
        });
    }

    private void showChart(List<ChartPoint> points) {
        if (isFinishing()) {
            return;
        }
        if (points.isEmpty()) {
            chart.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        chart.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);

        List<Entry> entries = new ArrayList<>(points.size());
        for (ChartPoint point : points) {
            float x = (point.bucketTs - rangeStartMs) / 1000f;
            entries.add(new Entry(x, point.avgValue));
        }

        LineDataSet dataSet = new LineDataSet(entries, getString(MetricLabels.titleRes(metric)));
        dataSet.setColor(accentColor);
        dataSet.setLineWidth(2f);
        dataSet.setDrawCircles(false);
        dataSet.setDrawValues(false);
        dataSet.setDrawFilled(true);
        dataSet.setFillColor(accentColor);
        dataSet.setFillAlpha(40);
        dataSet.setMode(LineDataSet.Mode.LINEAR);

        chart.setData(new LineData(dataSet));
        chart.invalidate();
    }

    /** Live per-metric breakdown, shown only for the currently monitored connection. */
    private void renderLiveRows(MetricSnapshot snapshot) {
        rowsContainer.removeAllViews();
        if (snapshot == null || connectionId == null || !connectionId.equals(snapshot.connectionId)) {
            return;
        }
        MetricReading reading = snapshot.get(metric);
        if (reading == null || reading.detailRows.isEmpty()) {
            return;
        }
        for (DetailRow row : reading.detailRows) {
            View rowView = getLayoutInflater().inflate(R.layout.list_detail_row, rowsContainer, false);
            ((TextView) rowView.findViewById(R.id.row_label)).setText(row.label);
            ((TextView) rowView.findViewById(R.id.row_value)).setText(row.value);
            LinearProgressIndicator bar = rowView.findViewById(R.id.row_bar);
            if (row.hasBar()) {
                int pct = (int) Math.round(Math.max(0, Math.min(1, row.fraction)) * 100);
                bar.setVisibility(View.VISIBLE);
                bar.setIndicatorColor(accentColor);
                bar.setProgressCompat(pct, false);
            }
            rowsContainer.addView(rowView);
        }
    }

    private String formatYValue(float value) {
        boolean prefsFahrenheit = new PreferenceHelper(this).useFahrenheit();
        switch (metric) {
            case CPU:
            case DISK:
                return String.format(Locale.US, "%.0f%%", value);
            case RAM:
                return Format.formatKb((long) (value * 1024)); // stored as MB
            case TEMPERATURE:
                return Format.formatTemp(value, prefsFahrenheit);
            case LOAD:
                return String.format(Locale.US, "%.1f", value);
            case NETWORK:
                return Format.formatRate(value, new PreferenceHelper(this).useBits());
            default:
                return String.valueOf(value);
        }
    }

    private int resolveTextColor() {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv, true)) {
            return tv.resourceId != 0 ? ContextCompat.getColor(this, tv.resourceId) : tv.data;
        }
        return Color.GRAY;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_detail, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.export_csv) {
            startCsvExport();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---- CSV export (B3) ----------------------------------------------------

    private final androidx.activity.result.ActivityResultLauncher<String> createDocument =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv"),
                    uri -> {
                        if (uri != null) {
                            new CsvExporter(this).export(connectionId, uri);
                        }
                    });

    private void startCsvExport() {
        String base = connectionName != null ? connectionName.replaceAll("[^a-zA-Z0-9-_]", "_") : "server";
        String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        createDocument.launch("perfmon-" + base + "-" + date + ".csv");
    }
}
