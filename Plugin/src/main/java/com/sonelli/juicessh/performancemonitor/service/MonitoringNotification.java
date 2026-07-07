package com.sonelli.juicessh.performancemonitor.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.sonelli.juicessh.performancemonitor.R;
import com.sonelli.juicessh.performancemonitor.activities.MainActivity;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;
import com.sonelli.juicessh.performancemonitor.model.MetricSnapshot;
import com.sonelli.juicessh.performancemonitor.model.MetricType;

import java.util.List;

/**
 * Builds the notifications used by {@link MonitoringService}: the silent ongoing
 * foreground notification with live headline metrics, and the one-shot notice
 * shown when monitoring ends unexpectedly.
 */
public final class MonitoringNotification {

    public static final int MONITORING_ID = 1001;
    /** Per-server "monitoring ended" notices use ENDED_ID_BASE + the monitor's slot. */
    public static final int ENDED_ID_BASE = 3000;
    /** Alert notifications use ALERT_ID_BASE + slot*NUM_METRICS + {@link MetricType#id}. */
    public static final int ALERT_ID_BASE = 2000;

    static final String CHANNEL_MONITORING = "channel_monitoring";
    static final String CHANNEL_ALERTS = "channel_alerts";

    private MonitoringNotification() {
    }

    static void ensureChannels(Context context) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        nm.createNotificationChannel(
                new NotificationChannelCompat.Builder(CHANNEL_MONITORING, NotificationManagerCompat.IMPORTANCE_LOW)
                        .setName(context.getString(R.string.notification_channel_monitoring))
                        .setDescription(context.getString(R.string.notification_channel_monitoring_description))
                        .setShowBadge(false)
                        .build());
        nm.createNotificationChannel(
                new NotificationChannelCompat.Builder(CHANNEL_ALERTS, NotificationManagerCompat.IMPORTANCE_HIGH)
                        .setName(context.getString(R.string.notification_channel_alerts))
                        .setDescription(context.getString(R.string.notification_channel_alerts_description))
                        .build());
    }

    /** A threshold alert. One notification id per metric, so repeats update in place. */
    static Notification buildAlert(Context context, String title, String text) {
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_monitor)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(content)
                .build();
    }

    /**
     * The ongoing foreground notification. {@code lines} holds one entry per
     * connected server ("BoltHub — CPU 4% · RAM 1.2 GB · Load 0.8"); all of them
     * are shown when the notification is expanded.
     */
    static Notification buildMonitoring(Context context, String title, String collapsedText, List<String> lines) {
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent disconnect = new Intent(context, MonitoringService.class)
                .setAction(MonitoringService.ACTION_DISCONNECT);
        PendingIntent disconnectIntent = PendingIntent.getService(context, 1, disconnect,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(context, CHANNEL_MONITORING)
                .setSmallIcon(R.drawable.ic_stat_monitor)
                .setContentTitle(title != null ? title : context.getString(R.string.app_name))
                .setContentText(collapsedText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(TextUtils.join("\n", lines)))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, context.getString(R.string.disconnect_all), disconnectIntent)
                .build();
    }

    /** The non-ongoing notice left behind when the session dies underneath us. */
    static Notification buildEnded(Context context, String connectionName) {
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(context, CHANNEL_MONITORING)
                .setSmallIcon(R.drawable.ic_stat_monitor)
                .setContentTitle(context.getString(R.string.monitoring_ended_title))
                .setContentText(connectionName != null
                        ? context.getString(R.string.monitoring_ended_text_named, connectionName)
                        : context.getString(R.string.monitoring_ended_text))
                .setAutoCancel(true)
                .setContentIntent(content)
                .build();
    }

    /** "CPU 42% · RAM 1.2 GB · Load 0.80" from whatever the snapshot has. */
    static String summaryText(Context context, MetricSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        appendHeadline(sb, "CPU", snapshot.get(MetricType.CPU));
        appendHeadline(sb, "RAM", snapshot.get(MetricType.RAM));
        appendHeadline(sb, "Load", snapshot.get(MetricType.LOAD));
        if (sb.length() == 0) {
            return context.getString(R.string.collecting_metrics);
        }
        return sb.toString();
    }

    private static void appendHeadline(StringBuilder sb, String label, MetricReading reading) {
        if (reading == null || reading.display == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(label).append(' ').append(reading.display);
    }
}
