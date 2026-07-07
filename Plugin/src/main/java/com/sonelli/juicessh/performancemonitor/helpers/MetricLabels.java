package com.sonelli.juicessh.performancemonitor.helpers;

import com.sonelli.juicessh.performancemonitor.R;
import com.sonelli.juicessh.performancemonitor.model.MetricType;

/**
 * Maps a {@link MetricType} to its display title and accent colour, so the
 * service (alert text) and the detail screen (chart) agree on labelling
 * without each re-deriving it.
 */
public final class MetricLabels {

    private MetricLabels() {
    }

    public static int titleRes(MetricType type) {
        switch (type) {
            case CPU:
                return R.string.cpu_usage;
            case RAM:
                return R.string.free_memory;
            case TEMPERATURE:
                return R.string.temperature;
            case LOAD:
                return R.string.load_average;
            case NETWORK:
                return R.string.network;
            case DISK:
            default:
                return R.string.disk;
        }
    }

    public static int accentColorRes(MetricType type) {
        switch (type) {
            case CPU:
                return R.color.metric_cpu;
            case RAM:
                return R.color.metric_ram;
            case TEMPERATURE:
                return R.color.metric_temp;
            case LOAD:
                return R.color.metric_load;
            case NETWORK:
                return R.color.metric_network;
            case DISK:
            default:
                return R.color.metric_disk;
        }
    }
}
