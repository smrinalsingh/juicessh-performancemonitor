package com.sonelli.juicessh.performancemonitor.controllers;

import android.content.Context;
import android.widget.Toast;

import com.sonelli.juicessh.performancemonitor.helpers.PreferenceHelper;
import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.views.MetricTileView;
import com.sonelli.juicessh.pluginlibrary.PluginClient;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class BaseController {

    private int sessionId;
    private String sessionKey;
    private PluginClient client;
    private MetricTileView tile;
    private final WeakReference<Context> context;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public BaseController(Context context) {
        this.context = new WeakReference<>(context);
    }

    public BaseController setSessionId(int sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    public BaseController setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
        return this;
    }

    public BaseController setPluginClient(PluginClient client) {
        this.client = client;
        return this;
    }

    public BaseController setTile(MetricTileView tile) {
        this.tile = tile;
        return this;
    }

    public String getString(int resource) {
        Context c = context.get();
        return c != null ? c.getString(resource) : null;
    }

    public int getSessionId() {
        return sessionId;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public PluginClient getPluginClient() {
        return client;
    }

    /** Current polling interval, read fresh each tick so Settings changes apply live. */
    protected long getIntervalMs() {
        Context c = context.get();
        return c != null ? new PreferenceHelper(c).getRefreshIntervalMs() : 2000L;
    }

    protected PreferenceHelper getPreferences() {
        Context c = context.get();
        return c != null ? new PreferenceHelper(c) : null;
    }

    /** Set the headline text only (errors / non-numeric states, no sparkline sample). */
    public void setText(String string) {
        // Ignore late callbacks that arrive after the controller was stopped so a
        // disconnected tile can't be overwritten with stale data.
        if (isRunning() && tile != null) {
            tile.setValue(string);
        }
    }

    /** Publish a numeric reading: update the headline and append to the sparkline history. */
    public void publish(double value, String display) {
        if (isRunning() && tile != null) {
            tile.setValue(display);
            tile.pushSample((float) value);
        }
    }

    protected void setDetailRows(List<DetailRow> rows) {
        if (tile != null) {
            tile.setDetailRows(rows);
        }
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public BaseController start() {
        isRunning.set(true);
        return this;
    }

    public void stop() {
        isRunning.set(false);
    }

    public void toast(String reason) {
        if (!isRunning()) {
            return; // suppress a stray error toast from a poll that raced past disconnect
        }
        Context c = context.get();
        if (c != null) {
            Toast.makeText(c, reason, Toast.LENGTH_SHORT).show();
        }
    }
}
