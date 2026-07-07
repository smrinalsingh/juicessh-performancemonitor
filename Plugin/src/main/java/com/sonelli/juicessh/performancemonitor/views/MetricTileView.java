package com.sonelli.juicessh.performancemonitor.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.card.MaterialCardView;
import com.sonelli.juicessh.performancemonitor.R;
import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.model.MetricHistory;

import java.util.ArrayList;
import java.util.List;

/**
 * A single dashboard tile: an accent-coloured Material card showing an icon, a
 * title, a large auto-resizing value and a {@link SparklineView} of its recent
 * history. Also carries the detail rows shown when the tile is tapped.
 *
 * <p>Controllers push updates via {@link #setValue(String)} and
 * {@link #pushSample(float)} rather than writing to a raw TextView, so the numeric
 * history and per-tile breakdown are retained for the sparkline and detail sheet.
 */
public class MetricTileView extends MaterialCardView {

    private static final int HISTORY_CAPACITY = 60;

    private final ImageView iconView;
    private final TextView titleView;
    private final AutoResizeTextView valueView;
    private final SparklineView sparklineView;

    private final MetricHistory history = new MetricHistory(HISTORY_CAPACITY);
    private List<DetailRow> detailRows = new ArrayList<>();

    private CharSequence title = "";
    private int accentColor = Color.DKGRAY;

    public MetricTileView(@NonNull Context context) {
        this(context, null);
    }

    public MetricTileView(@NonNull Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MetricTileView(@NonNull Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        LayoutInflater.from(context).inflate(R.layout.view_metric_tile, this, true);
        iconView = findViewById(R.id.tile_icon);
        titleView = findViewById(R.id.tile_title);
        valueView = findViewById(R.id.tile_value);
        sparklineView = findViewById(R.id.tile_sparkline);

        float density = getResources().getDisplayMetrics().density;
        setRadius(20f * density);
        setCardElevation(3f * density);
        setUseCompatPadding(true);
        setClickable(true);
        setFocusable(true);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.MetricTileView);
            title = a.getString(R.styleable.MetricTileView_tileTitle);
            int iconRes = a.getResourceId(R.styleable.MetricTileView_tileIcon, 0);
            accentColor = a.getColor(R.styleable.MetricTileView_tileAccent, Color.DKGRAY);
            a.recycle();

            titleView.setText(title != null ? title : "");
            if (iconRes != 0) {
                iconView.setImageResource(iconRes);
            }
            setCardBackgroundColor(accentColor);
        }
    }

    /** Updates the large headline value shown on the tile. */
    public void setValue(String display) {
        if (display != null && !display.contentEquals(valueView.getText())) {
            valueView.setText(display);
        }
    }

    /** Appends a numeric sample to the history and refreshes the sparkline. */
    public void pushSample(float value) {
        history.add(value);
        sparklineView.setData(history.toArray());
    }

    public void setDetailRows(List<DetailRow> rows) {
        this.detailRows = rows != null ? rows : new ArrayList<>();
    }

    public List<DetailRow> getDetailRows() {
        return detailRows;
    }

    public float[] getHistory() {
        return history.toArray();
    }

    public CharSequence getTitle() {
        return title != null ? title : "";
    }

    public int getAccentColor() {
        return accentColor;
    }

    /** Clears the value, history and detail rows (on disconnect). */
    public void reset() {
        valueView.setText("-");
        history.clear();
        sparklineView.setData(new float[0]);
        detailRows = new ArrayList<>();
    }
}
