package io.github.unnyanr11.mediascraper;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Parallel HTTP downloads for publicly accessible direct files. Supports resume
 * via HTTP Range if the server responds with 206 and safe .part files.
 * Authenticated browser downloads remain in MediaDownloader.
 */
public final class DownloadQueue implements AutoCloseable {
    private final ExecutorService workers;
    private final HttpClient client;
    private final int concurrency;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public DownloadQueue(int concurrency) {
        this.concurrency = Math.max(1, Math.min(20, concurrency));
        workers = Executors.newFixedThreadPool(this.concurrency);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public void stop() { stopped.set(true); }
    public boolean stopped() { return stopped.get(); }

    public int downloadAll(List<MediaItem> items, Path folder, Consumer<String> status) throws InterruptedException {
        List<Future<Boolean>> futures = new ArrayList<>();
        for (MediaItem item : items) {
            if (stopped.get()) break;
            if (!item.type().equals("image") && !item.type().equals("video")) continue;
            futures.add(workers.submit(() -> fetch(item, folder, status)));
        }
        int completed = 0;
        for (Future<Boolean> f : futures) {
            if (stopped.get()) break;
            try { if (f.get()) completed++; }
            catch (ExecutionException e) { status.accept("Download error: " + e.getCause().getMessage()); }
        }
        return completed;
    }

    public boolean fetch(MediaItem item, Path folder, Consumer<String> status) {
        if (stopped.get()) return false;
        try {
            URI uri = URI.create(item.url());
            if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null) return false;
            Files.createDirectories(folder);
            String name = item.type() + "_" + Integer.toHexString(item.url().hashCode()) +
                "_" + Integer.toHexString(item.url().length());
            Path part = folder.resolve(name + ".part");
            long existing = Files.exists(part) ? Files.size(part) : 0;
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3))
                .header("User-Agent", "MediaScraperFull/0.2");
            if (existing > 0) builder.header("Range", "bytes=" + existing + "-");
            HttpResponse<Path> response = client.send(builder.GET().build(),
                HttpResponse.BodyHandlers.ofFile(part,
                    existing > 0 ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                                 : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING}));
            int code = response.statusCode();
            if (code == 401 || code == 403 || code == 429) {
                stopped.set(true);
                status.accept("Access restricted or rate limited (" + code + "); queue paused");
                return false;
            }
            if (code != 200 && code != 206) return false;
            String mime = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (!mime.startsWith("image/") && !mime.startsWith("video/")) {
                Files.deleteIfExists(part);
                return false;
            }
            // A server can ignore Range and return the whole file: avoid appending duplicate bytes.
            if (existing > 0 && code == 200) {
                Files.deleteIfExists(part);
                return fetch(item, folder, status);
            }
            String ext = extension(mime);
            Path destination = folder.resolve(name + "." + ext);
            Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING);
            status.accept("Saved: " + destination.getFileName());
            return true;
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            status.accept("Download failed: " + item.url() + " — " + e.getMessage());
            return false;
        }
    }

    private static String extension(String type) {
        if (type.contains("jpeg")) return "jpg";
        for (String ext : List.of("png", "webp", "gif", "avif", "mp4", "webm")) {
            if (type.contains(ext)) return ext;
        }
        return type.contains("quicktime") ? "mov" : "bin";
    }

    @Override public void close() {
        stopped.set(true);
        workers.shutdownNow();
    }
}
