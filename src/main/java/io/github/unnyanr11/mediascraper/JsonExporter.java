package io.github.unnyanr11.mediascraper;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes UTF-8 media listings and metadata-rich source backups. */
public final class JsonExporter {
    private JsonExporter() {}

    public static void write(Path destination, List<MediaItem> items) throws IOException {
        writeValue(destination, items);
    }

    static void writeBackup(Path destination, String source, List<ScanStore.HistoryEntry> entries) throws IOException {
        writeValue(destination, new Backup(source, 1, entries));
    }

    private record Backup(String source, int version, List<ScanStore.HistoryEntry> entries) {}

    private static void writeValue(Path destination, Object value) throws IOException {
        try (var writer = Files.newBufferedWriter(destination)) {
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(value, writer);
            writer.write(System.lineSeparator());
        }
    }
}
