package com.sonelli.juicessh.performancemonitor.parsers;

import com.sonelli.juicessh.performancemonitor.helpers.Format;
import com.sonelli.juicessh.performancemonitor.model.DetailRow;
import com.sonelli.juicessh.performancemonitor.model.MetricReading;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code df -Pk} output into root filesystem usage% (headline) with every
 * real mount as a detail row. Pseudo filesystems (tmpfs/devtmpfs) are skipped.
 * {@code -Pk} forces POSIX output in 1024-byte blocks so the size/used columns
 * are always KiB, regardless of POSIXLY_CORRECT.
 */
public class DfParser {

    // filesystem  1024-blocks  used  available  capacity%  mounted-on
    private static final Pattern ROW =
            Pattern.compile("^(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)%\\s+(.+)$");

    /**
     * @return the reading (value = root usage %; rows-only if no "/" mount was
     * listed), or null if no mount rows were parseable at all.
     */
    public MetricReading parse(List<String> lines) {
        final List<DetailRow> rows = new ArrayList<>();
        double rootPct = Double.NaN;

        for (String line : lines) {
            Matcher m = ROW.matcher(line.trim());
            if (!m.find()) {
                continue; // header or unmatched line
            }
            String fs = m.group(1);
            if (fs.equals("tmpfs") || fs.equals("devtmpfs") || fs.equals("none")) {
                continue;
            }
            long sizeKb;
            long usedKb;
            double pct;
            try {
                sizeKb = Long.parseLong(m.group(2));
                usedKb = Long.parseLong(m.group(3));
                pct = Double.parseDouble(m.group(5));
            } catch (NumberFormatException e) {
                continue;
            }
            String mount = m.group(6).trim();
            if (sizeKb <= 0) {
                continue;
            }
            if (mount.equals("/")) {
                rootPct = pct;
            }
            rows.add(new DetailRow(mount,
                    String.format(Locale.US, "%.0f%%  (%s / %s)", pct,
                            Format.formatKb(usedKb), Format.formatKb(sizeKb)),
                    pct / 100.0));
        }

        if (rows.isEmpty()) {
            return null;
        }
        if (Double.isNaN(rootPct)) {
            return MetricReading.rowsOnly(rows);
        }
        return MetricReading.of(rootPct, String.format(Locale.US, "%.0f%%", rootPct), rows);
    }
}
