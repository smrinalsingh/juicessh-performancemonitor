package com.sonelli.juicessh.performancemonitor.adapters;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.cursoradapter.widget.CursorAdapter;

import com.sonelli.juicessh.performancemonitor.R;
import com.sonelli.juicessh.pluginlibrary.PluginContract;

import java.util.UUID;

/**
 * Loads JuiceSSH connections from a cursor and provides an adapter
 * that can be used in a ListView or Spinner. You can optionally pass
 * a {@link com.sonelli.juicessh.pluginlibrary.PluginContract.Connections.TYPE}
 * to the constructor to filter down the list to a specific connection type.
 */
public class ConnectionSpinnerAdapter extends CursorAdapter {

    private final LayoutInflater inflater;
    private final int type;

    /**
     * Loads all JuiceSSH connections from a cursor ready for a ListView/Spinner
     */
    public ConnectionSpinnerAdapter(Context context) {
        this(context, -1);
    }

    /**
     * Loads JuiceSSH connections of a specific type ready for a ListView/Spinner
     */
    public ConnectionSpinnerAdapter(Context context, int type) {
        super(context, null, false);
        this.type = type;
        this.inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    /**
     * Returns the UUID connection ID for the item at a given position, or null if not available
     */
    public UUID getConnectionId(int position) {
        UUID id = null;
        if (getCursor() != null) {
            if (getCursor().moveToPosition(position)) {
                int idIndex = getCursor().getColumnIndex(PluginContract.Connections.COLUMN_ID);
                if (idIndex > -1) {
                    id = UUID.fromString(getCursor().getString(idIndex));
                }
            }
        }
        return id;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup viewGroup) {
        return inflater.inflate(R.layout.spinner_list_item, viewGroup, false);
    }

    @Override
    public int getItemViewType(int position) {
        return 0;
    }

    @Override
    public int getViewTypeCount() {
        return 1;
    }

    @Override
    public void bindView(View view, final Context context, Cursor cursor) {
        int nameColumn = cursor.getColumnIndex(PluginContract.Connections.COLUMN_NAME);
        int typeColumn = cursor.getColumnIndex(PluginContract.Connections.COLUMN_TYPE);

        if (nameColumn > -1) {
            TextView textView = view.findViewById(android.R.id.text1);
            textView.setText(cursor.getString(nameColumn));

            // Non-SSH connections (Mosh/telnet/local) can't run commands, so disable them.
            if (type != -1) {
                if (cursor.getInt(typeColumn) != type) {
                    textView.setOnTouchListener((v, motionEvent) -> {
                        Toast.makeText(context, context.getString(R.string.only_ssh_connections_are_supported), Toast.LENGTH_SHORT).show();
                        return false;
                    });
                    textView.setTextColor(themeColor(context, android.R.attr.textColorSecondary));
                } else {
                    textView.setOnTouchListener(null);
                    textView.setTextColor(themeColor(context, android.R.attr.textColorPrimary));
                }
            }
        }
    }

    /** Resolves a theme colour attribute so spinner text is legible in light and dark. */
    private static int themeColor(Context context, int attr) {
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, tv, true)) {
            return tv.resourceId != 0 ? ContextCompat.getColor(context, tv.resourceId) : tv.data;
        }
        return Color.GRAY;
    }
}
