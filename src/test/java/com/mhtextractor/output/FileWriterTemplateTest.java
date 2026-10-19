package com.mhtextractor.output;

import com.mhtextractor.engine.PreviewNamingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileWriterTemplateTest {
    @TempDir Path tmp;

    @Test void basenameUsesKnownExtensionsAndPreservesFullNameAndMtime() throws Exception {
        var time = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        String template = "{filename}|{basename}|{datetime}|{date}|{time}\\n";
        String expected = "plain.html|plain|2026-01-02 03:04:05|2026-01-02|03:04:05\n";
        assertEquals(expected, PreviewNamingService.applyTemplate(template, "plain.html", time));
        Path out = tmp.resolve("template.txt");
        assertTrue(FileWriter.appendTextToFile(out.toString(), "body", "plain.html", time, template, "", ""));
        assertEquals(expected + "body", Files.readString(out));
        System.out.println("C2 OUTPUT: " + Files.readString(out).replace("\n", "\\n"));
        for (var entry : Map.of("a.mht", "a", "a.mhtml", "a", "a.HTM", "a", "a.b.HTML", "a.b",
                "a.zip", "a.zip", "noext", "noext", ".html", ".html").entrySet()) {
            assertEquals(entry.getKey() + "|" + entry.getValue(),
                    FileWriter.processTemplate("{filename}|{basename}", entry.getKey(), null));
        }
        assertEquals("||||", FileWriter.processTemplate("{filename}|{basename}|{datetime}|{date}|{time}", null, null));
        assertEquals("{basename}.mht", FileWriter.processTemplate("{filename}", "{basename}.mht", null));
    }
}
