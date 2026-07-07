package com.sonelli.juicessh.performancemonitor.helpers;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.sonelli.juicessh.performancemonitor.R;
import com.sonelli.juicessh.performancemonitor.data.AppDatabase;
import com.sonelli.juicessh.performancemonitor.data.MetricSampleEntity;
import com.sonelli.juicessh.performancemonitor.model.MetricType;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streams a connection's persisted metric history to a user-chosen file (via
 * SAF) as CSV, off the main thread. Columns: ISO-8601 timestamp, metric name,
 * value (in the metric's stored unit).
 */
public class CsvExporter {

    private static final String TAG = "CsvExporter";

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public CsvExporter(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Writes every stored sample for {@code connectionId} to {@code target}. */
    public void export(String connectionId, Uri target) {
        executor.execute(() -> {
            int rows = 0;
            boolean ok = true;
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
            try (OutputStream os = context.getContentResolver().openOutputStream(target);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {

                writer.write("timestamp,metric,value\n");
                List<MetricSampleEntity> samples = AppDatabase.getInstance(context)
                        .metricSampleDao().getRawSince(connectionId, 0);
                for (MetricSampleEntity sample : samples) {
                    MetricType type = MetricType.fromId(sample.metric);
                    writer.write(iso.format(new Date(sample.timestamp)));
                    writer.write(',');
                    writer.write(type != null ? type.name().toLowerCase(Locale.US) : String.valueOf(sample.metric));
                    writer.write(',');
                    writer.write(String.valueOf(sample.value));
                    writer.write('\n');
                    rows++;
                }
            } catch (Exception e) {
                ok = false;
                Log.e(TAG, "CSV export failed", e);
            }

            final boolean success = ok;
            final int count = rows;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    Toast.makeText(context,
                            success ? context.getString(R.string.export_csv_done, count)
                                    : context.getString(R.string.export_csv_failed),
                            Toast.LENGTH_LONG).show());
            executor.shutdown();
        });
    }
}
