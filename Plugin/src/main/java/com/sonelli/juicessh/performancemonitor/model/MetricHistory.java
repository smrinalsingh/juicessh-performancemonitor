package com.sonelli.juicessh.performancemonitor.model;

/**
 * Fixed-capacity ring buffer of recent numeric samples for a single metric.
 * Backs the sparkline drawn on each tile. Thread-safe: controllers append from
 * the JuiceSSH callback thread while the UI reads a snapshot to draw.
 */
public class MetricHistory {

    private final float[] buffer;
    private int head;   // index of the next write
    private int count;  // number of valid samples (<= capacity)

    public MetricHistory(int capacity) {
        this.buffer = new float[Math.max(1, capacity)];
    }

    public synchronized void add(float value) {
        buffer[head] = value;
        head = (head + 1) % buffer.length;
        if (count < buffer.length) {
            count++;
        }
    }

    /** Returns the samples oldest-to-newest as a fresh array. */
    public synchronized float[] toArray() {
        float[] out = new float[count];
        int start = (head - count + buffer.length) % buffer.length;
        for (int i = 0; i < count; i++) {
            out[i] = buffer[(start + i) % buffer.length];
        }
        return out;
    }

    public synchronized int size() {
        return count;
    }

    public synchronized void clear() {
        head = 0;
        count = 0;
    }
}
