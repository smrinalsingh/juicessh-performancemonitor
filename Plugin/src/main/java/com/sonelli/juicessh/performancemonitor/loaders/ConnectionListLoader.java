package com.sonelli.juicessh.performancemonitor.loaders;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.CursorLoader;
import androidx.loader.content.Loader;

import com.sonelli.juicessh.performancemonitor.adapters.ConnectionSpinnerAdapter;
import com.sonelli.juicessh.pluginlibrary.PluginContract;

public class ConnectionListLoader implements LoaderManager.LoaderCallbacks<Cursor> {

    private final Context context;
    private final ConnectionSpinnerAdapter adapter;

    /**
     * Creates a Loader that fetches all connections from the JuiceSSH content
     * provider on a background thread and populates the associated adapter.
     */
    public ConnectionListLoader(Context context, ConnectionSpinnerAdapter adapter) {
        this.context = context;
        this.adapter = adapter;
    }

    @NonNull
    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle bundle) {
        return new CursorLoader(
                context,
                PluginContract.Connections.CONTENT_URI,
                PluginContract.Connections.PROJECTION,
                null,
                null,
                PluginContract.Connections.SORT_ORDER_DEFAULT
        );
    }

    @Override
    public void onLoadFinished(@NonNull Loader<Cursor> cursorLoader, Cursor cursor) {
        if (adapter != null) {
            adapter.swapCursor(cursor);
        }
    }

    @Override
    public void onLoaderReset(@NonNull Loader<Cursor> cursorLoader) {
        if (adapter != null) {
            adapter.swapCursor(null);
        }
    }
}
