package com.sonelli.juicessh.performancemonitor.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Lightweight trend line for a metric's recent history. Autoscales the vertical
 * axis to the min/max of the current window and draws a stroked line with a soft
 * fill beneath it. No external charting dependency.
 */
public class SparklineView extends View {

    private float[] data = new float[0];
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SparklineView(Context context) {
        this(context, null);
    }

    public SparklineView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SparklineView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2f * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        setColor(0x66FFFFFF); // translucent white default (looks good on the accent tiles)
    }

    /** Sets the line colour; the fill uses a lower-alpha version of the same hue. */
    public void setColor(int color) {
        linePaint.setColor(color);
        fillPaint.setColor((color & 0x00FFFFFF) | 0x22000000);
        invalidate();
    }

    public void setData(float[] data) {
        this.data = data != null ? data : new float[0];
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (data.length < 2) {
            return;
        }

        float w = getWidth();
        float h = getHeight();
        float pad = linePaint.getStrokeWidth();

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float v : data) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        float range = max - min;
        if (range <= 0f) {
            range = 1f; // flat line — draw it centred
        }

        int n = data.length;
        Path line = new Path();
        Path fill = new Path();
        for (int i = 0; i < n; i++) {
            float x = pad + (w - 2 * pad) * i / (n - 1);
            float norm = (data[i] - min) / range;
            float y = (h - pad) - norm * (h - 2 * pad);
            if (i == 0) {
                line.moveTo(x, y);
                fill.moveTo(x, h);
                fill.lineTo(x, y);
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
        }
        fill.lineTo(pad + (w - 2 * pad), h);
        fill.close();

        canvas.drawPath(fill, fillPaint);
        canvas.drawPath(line, linePaint);
    }
}
