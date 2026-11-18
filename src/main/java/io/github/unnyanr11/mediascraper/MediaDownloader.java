package io.github.unnyanr11.mediascraper;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.RequestOptions;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Downloads discovered, directly accessible media using the selected scan's isolated session. */
public final class MediaDownloader {
    private static final Set<String> TYPES = Set.of("image", "video");
    private MediaDownloader() {}

    public static boolean download(BrowserContext context, MediaItem item, Path destination,
                                   int index, AtomicBoolean stopped, Consumer<String> status) {
        if (!TYPES.contains(item.type()) || stopped.get()) return false;
        try {
            URI uri = URI.create(item.url());
            if (!Set.of("http", "https").contains(uri.getScheme())) return false;
            Files.createDirectories(destination);
            try (APIResponse response = context.request().get(item.url(),
                    RequestOptions.create().setTimeout(45_000).setMaxRedirects(5))) {
                if (response.status() == 401 || response.status() == 403 || response.status() == 429) {
                    status.accept("Access restricted or rate limited: " + response.status() + "; stopping downloads");
                    stopped.set(true);
                    return false;
                }
                if (!response.ok()) {
                    status.accept("Skipped HTTP " + response.status() + ": " + item.url());
                    return false;
                }
                String mime = response.headers().getOrDefault("content-type", "").toLowerCase();
                if (!mime.startsWith("image/") && !mime.startsWith("video/")) {
                    status.accept("Skipped non-media response: " + item.url());
                    return false;
                }
                byte[] bytes = response.body();
                if (bytes.length == 0) return false;
                String extension = extension(mime);
                String name = String.format("%06d_%s.%s", index, item.type(), extension);
                Path target = destination.resolve(name);
                Path tmp = Files.createTempFile(destination, ".media-", ".part");
                try {
                    Files.write(tmp, bytes);
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(tmp); }
                return true;
            }
        } catch (Exception e) {
            status.accept("Download skipped: " + e.getMessage());
            return false;
        }
    }

    private static String extension(String mime) {
        if (mime.contains("jpeg")) return "jpg";
        if (mime.contains("png")) return "png";
        if (mime.contains("webp")) return "webp";
        if (mime.contains("gif")) return "gif";
        if (mime.contains("avif")) return "avif";
        if (mime.contains("webm")) return "webm";
        if (mime.contains("quicktime")) return "mov";
        if (mime.contains("mp4")) return "mp4";
        return "bin";
    }
}
