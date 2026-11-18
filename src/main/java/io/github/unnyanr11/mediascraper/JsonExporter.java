package io.github.unnyanr11.mediascraper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exports discovered links as portable UTF-8 JSON without exporting authentication material. */
public final class JsonExporter {
    private JsonExporter() {}

    public static void write(Path destination, List<MediaItem> items) throws IOException {
        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < items.size(); i++) {
            MediaItem item = items.get(i);
            json.append("  {\"type\":\"").append(escape(item.type()))
                .append("\",\"postUrl\":\"").append(escape(item.postUrl()))
                .append("\",\"url\":\"").append(escape(item.url())).append("\"}");
            if (i + 1 < items.size()) json.append(",");
            json.append("\n");
        }
        json.append("]\n");
        Files.writeString(destination, json);
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> result.append("\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (ch < 0x20) result.append(String.format("\\u%04x", (int) ch));
                    else result.append(ch);
                }
            }
        }
        return result.toString();
    }
}
