package com.sonelli.juicessh.performancemonitor.parsers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SectionSplitterTest {

    @Test
    public void splitsLinesIntoNamedSections() {
        Map<String, List<String>> sections = SectionSplitter.split(Arrays.asList(
                "###PMON:cpu",
                "cpu  1 2 3 4 5",
                "cpu0 1 2 3 4 5",
                "###PMON:mem",
                "MemTotal: 100 kB",
                "###PMON:disk"));

        assertEquals(Arrays.asList("cpu  1 2 3 4 5", "cpu0 1 2 3 4 5"), sections.get("cpu"));
        assertEquals(Arrays.asList("MemTotal: 100 kB"), sections.get("mem"));
        assertTrue(sections.get("disk").isEmpty());
        assertNull(sections.get("net"));
    }

    @Test
    public void stripsCarriageReturnsAndBlankLines() {
        Map<String, List<String>> sections = SectionSplitter.split(Arrays.asList(
                "###PMON:load\r",
                "0.5 0.4 0.3 1/100 999\r",
                "",
                "   "));

        assertEquals(Arrays.asList("0.5 0.4 0.3 1/100 999"), sections.get("load"));
    }

    @Test
    public void ignoresLinesBeforeFirstMarkerIncludingCommandEcho() {
        // A PTY that echoes the command back produces a line containing every
        // marker; it must not be mistaken for marker lines or section content.
        Map<String, List<String>> sections = SectionSplitter.split(Arrays.asList(
                "echo '###PMON:cpu'; cat /proc/stat; echo '###PMON:mem'",
                "###PMON:cpu",
                "cpu 1 2 3 4 5"));

        assertEquals(1, sections.size());
        assertEquals(Arrays.asList("cpu 1 2 3 4 5"), sections.get("cpu"));
    }
}
