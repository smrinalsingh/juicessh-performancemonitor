package com.sonelli.juicessh.performancemonitor.parsers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits the output of the batched poll command into per-metric sections.
 * The command echoes a {@code ###PMON:<name>} marker line before each data
 * source; everything until the next marker belongs to that section. Lines
 * before the first marker (e.g. a shell echoing the command itself) are
 * dropped, as are blank lines.
 */
public final class SectionSplitter {

    public static final String MARKER = "###PMON:";

    private SectionSplitter() {
    }

    public static Map<String, List<String>> split(List<String> lines) {
        Map<String, List<String>> sections = new HashMap<>();
        List<String> current = null;

        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            String trimmed = line.trim();
            if (trimmed.startsWith(MARKER)) {
                current = new ArrayList<>();
                sections.put(trimmed.substring(MARKER.length()).trim(), current);
            } else if (current != null && !trimmed.isEmpty()) {
                current.add(line);
            }
        }
        return sections;
    }
}
