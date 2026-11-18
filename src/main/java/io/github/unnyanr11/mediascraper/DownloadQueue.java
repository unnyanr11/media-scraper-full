package io.github.unnyanr11.mediascraper;

import com.microsoft.playwright.options.Cookie;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

final class DownloadQueue implements AutoCloseable {
    private static final Set<Integer> RESTRICTED = Set.of(401, 403, 429);
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private final ExecutorService workers;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Set<InputStream> active = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> connecting = ConcurrentHashMap.newKeySet();
    private record ClientKey(TaskOptions options, List<Cookie> cookies) {}
    private final Map<ClientKey, HttpClient> clients = new HashMap<>();
    private final Object refreshLock = new Object();
    private final Map<String, Long> nextRequest = new HashMap<>();
    private final Set<String> blockedHosts = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final ScanStore store;
    private final NetworkSettings network;
    private final Consumer<String> status;

    DownloadQueue(ScanStore store, int concurrency, Consumer<String> status) {
        this(store, concurrency, status, NetworkSettings.DEFAULT);
    }

    DownloadQueue(ScanStore store, int concurrency, Consumer<String> status, NetworkSettings network) {
        this.store = store;
        this.network = network;
        this.status = status;
        workers = Executors.newFixedThreadPool(Math.clamp(concurrency, 1, 20));
    }

    void pause() {
        paused.set(true);
        connecting.forEach(request -> request.cancel(true));
        active.forEach(DownloadQueue::closeBody);
    }

    boolean isPaused() { return paused.get(); }

    int download(List<ScanStore.Transfer> jobs, TaskOptions currentOptions, List<Cookie> currentCookies)
            throws SQLException, InterruptedException, IOException {
        List<Future<Result>> results = new ArrayList<>();
        Set<String> scheduled = new HashSet<>();
        List<ScanStore.Transfer> duplicates = new ArrayList<>();
        for (var storedJob : jobs) {
            if (isPaused()) break;
            var task = storedJob.options();
            var job = new ScanStore.Transfer(storedJob.source(), storedJob.item(), storedJob.folder(),
                new TaskOptions(task.cookies(), task.proxy(), task.rules(), network));
            if (job.options().needsPastedCookies()) continue;
            if (!scheduled.add(job.folder() + "|" + fileKey(job.item()))) {
                duplicates.add(job);
                continue;
            }
            List<Cookie> cookies = job.options().equals(currentOptions) ? currentCookies : null;
            results.add(workers.submit(() -> {
                for (int attempt = 0;; attempt++) {
                    try {
                        return transfer(job, cookies, true);
                    } catch (IOException e) {
                        if (isPaused()) return new Result(job, false);
                        if (e instanceof PlaylistException || attempt >= network.retries()) throw e;
                    }
                }
            }));
        }
        int count = 0;
        for (var future : results) {
            try {
                Result result = future.get();
                if (result.saved) {
                    store.completed(result.job);
                    count++;
                }
            } catch (ExecutionException e) {
                status.accept("Download remains pending · " + (e.getCause() instanceof PlaylistException
                    ? e.getCause().getMessage() : e.getCause().getClass().getSimpleName()));
            }
        }
        for (var job : duplicates) {
            if (findCompleted(job.folder(), fileKey(job.item()))) store.completed(job);
        }
        return count;
    }

    private record Result(ScanStore.Transfer job, boolean saved) {}
    private record Resume(String url, String name, String value, long length) {}

    private Result transfer(ScanStore.Transfer job, List<Cookie> cookies, boolean canRefresh)
            throws IOException, InterruptedException, SQLException {
        MediaItem item = job.item();
        Path folder = job.folder();
        String key = fileKey(item);
        Files.createDirectories(folder);
        if (findCompleted(folder, key)) return new Result(job, true);
        if (isPaused()) return new Result(job, false);
        URI uri = URI.create(item.url());
        String host = uri.getHost();
        if (blockedHosts.contains(host)) return new Result(job, false);
        if (item.type().equals("hls")) return new Result(job, downloadPlaylist(job, cookies, key));
        Path part = folder.resolve(key + ".part");
        Path metadata = folder.resolve(key + ".resume");
        Resume resume = readResume(metadata, part, item.url());
        long offset = resume == null ? 0 : Files.size(part);
        HttpRequest.Builder request = requestBuilder(uri);
        if (offset > 0) request.header("Range", "bytes=" + offset + "-").header("If-Range", resume.value);
        waitForHost(host);
        if (isPaused() || blockedHosts.contains(host)) return new Result(job, false);
        var response = request(client(job.options(), cookies), request.GET().build());
        if (response == null) return new Result(job, false);
        int code = response.statusCode();
        boolean expired = (code == 403 || code == 404 || code == 410) && canRefresh && MediaItem.isSigned(item.url())
            && !item.url().equals(item.postUrl());
        try (InputStream body = response.body()) {
            if (RESTRICTED.contains(code) && !expired) {
                blockedHosts.add(host);
                status.accept("HTTP " + code + " from " + host + "; remaining downloads for that host are pending");
                return new Result(job, false);
            }
            if (!expired && (code == 408 || code >= 500 && code <= 599)) {
                throw new IOException("Temporary HTTP " + code);
            } else if (!expired && code == 416 && offset > 0) {
                Files.deleteIfExists(metadata);
            } else if (!expired && (code == 200 || code == 206)) {
                String encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
                if (!"identity".equalsIgnoreCase(encoding)) throw new IOException("Unexpected download encoding");
                String mime = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0]
                    .strip().toLowerCase(Locale.ROOT);
                if (!matchesType(item, mime)) throw new IOException("Unexpected download content type: " + mime);
                long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (code == 206) {
                    var range = CONTENT_RANGE.matcher(response.headers().firstValue("Content-Range").orElse(""));
                    if (offset == 0 || !range.matches() || Long.parseLong(range.group(1)) != offset
                        || Long.parseLong(range.group(3)) != resume.length
                        || Long.parseLong(range.group(2)) != resume.length - 1
                        || (length >= 0 && length != resume.length - offset)
                        || !response.headers().firstValue(resume.name).orElse(resume.value).equals(resume.value)) {
                        throw new IOException("Invalid partial response");
                    }
                    length = resume.length;
                } else {
                    offset = 0;
                    Files.deleteIfExists(metadata);
                    Files.deleteIfExists(part);
                    Resume next = validator(response, item.url(), length);
                    if (next != null) Files.write(metadata, List.of(next.url, next.name, next.value, Long.toString(next.length)));
                }
                if (!copy(body, part, offset, length)) return new Result(job, false);
                Path destination = folder.resolve(key + "." + extension(uri, mime));
                Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(metadata);
                status.accept("Saved " + destination.getFileName());
                return new Result(job, true);
            } else if (!expired) {
                status.accept("HTTP " + code + " from " + host + "; download remains pending");
                return new Result(job, false);
            }
        }
        if (!canRefresh || isPaused()) return new Result(job, false);
        if (!expired) return transfer(job, cookies, false);
        ScanService.Refresh refresh;
        synchronized (refreshLock) {
            if (isPaused() || blockedHosts.contains(host)) return new Result(job, false);
            refresh = ScanService.refresh(job.options(), item, status);
            if (refresh == null) {
                if (code == 403) blockedHosts.add(host);
                return new Result(job, false);
            }
        }
        store.record(job.source(), refresh.item());
        return transfer(new ScanStore.Transfer(job.source(), refresh.item(), folder, job.options()), refresh.cookies(), false);
    }

    private static final class PlaylistException extends IOException {
        private PlaylistException(String message) { super(message); }
    }

    private HttpRequest.Builder requestBuilder(URI uri) {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(network.timeoutSeconds()))
            .header("User-Agent", network.userAgent().isBlank() ? "MediaScraperFull/0.2" : network.userAgent())
            .header("Accept-Encoding", "identity");
        network.headers().forEach(request::header);
        return request;
    }

    private HttpResponse<InputStream> playlistRequest(HttpClient client, String url)
            throws IOException, InterruptedException {
        URI uri = ScanService.webUri(url);
        waitForHost(uri.getHost());
        if (isPaused() || blockedHosts.contains(uri.getHost())) return null;
        var reply = request(client, requestBuilder(uri).GET().build());
        if (reply == null || reply.statusCode() == 200) return reply;
        reply.body().close();
        if (RESTRICTED.contains(reply.statusCode())) blockedHosts.add(uri.getHost());
        throw new PlaylistException("HLS resource returned HTTP " + reply.statusCode());
    }

    private boolean downloadPlaylist(ScanStore.Transfer job, List<Cookie> cookies, String key)
            throws IOException, InterruptedException {
        HttpClient client = client(job.options(), cookies);
        String url = job.item().url();
        HlsPlaylist playlist = null;
        for (int depth = 0; depth < 4 && !isPaused(); depth++) {
            var reply = playlistRequest(client, url);
            if (reply == null) return false;
            try (var body = reply.body()) {
                active.add(body);
                var timeout = timer.schedule(() -> closeBody(body), network.timeoutSeconds(), TimeUnit.SECONDS);
                try {
                    byte[] text = body.readNBytes(2_000_001);
                    if (text.length > 2_000_000) throw new PlaylistException("HLS playlist exceeds 2 MB");
                    try {
                        playlist = HlsPlaylist.parse(new String(text, StandardCharsets.UTF_8), reply.uri().toString());
                    } catch (IOException e) {
                        throw new PlaylistException(e.getMessage());
                    }
                } finally {
                    timeout.cancel(false);
                    active.remove(body);
                }
            }
            if (playlist.variant() == null) break;
            url = playlist.variant();
        }
        if (isPaused()) return false;
        if (playlist == null || playlist.variant() != null) throw new PlaylistException("Too many HLS playlist levels");
        Path cache = job.folder().resolve(key + ".segments");
        Files.createDirectories(cache);
        Path merged = job.folder().resolve(key + ".part");
        try (var out = Files.newOutputStream(merged)) {
            for (int index = 0; index < playlist.parts().size(); index++) {
                if (isPaused()) return false;
                String partUrl = playlist.parts().get(index);
                String id = fileKey(new MediaItem(url, partUrl, "segment"));
                Path savedPart = cache.resolve(index + "-" + id);
                if (!Files.isRegularFile(savedPart)) {
                    var reply = playlistRequest(client, partUrl);
                    if (reply == null) return false;
                    Path pending = cache.resolve("pending");
                    try (var body = reply.body()) {
                        String mime = reply.headers().firstValue("Content-Type").orElse("");
                        if (mime.contains("text/html") || mime.contains("application/json")) {
                            throw new PlaylistException("HLS segment response was not media");
                        }
                        long bytes = reply.headers().firstValueAsLong("Content-Length").orElse(-1);
                        if (!copy(body, pending, 0, bytes)) return false;
                    }
                    Files.move(pending, savedPart, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.copy(savedPart, out);
            }
        }
        if (isPaused()) return false;
        Path destination = job.folder().resolve(key + "." + playlist.extension());
        Files.move(merged, destination, StandardCopyOption.REPLACE_EXISTING);
        try (var entries = Files.list(cache)) {
            for (Path entry : entries.toList()) Files.deleteIfExists(entry);
        }
        Files.deleteIfExists(cache);
        status.accept("Saved " + destination.getFileName());
        return true;
    }

    private HttpResponse<InputStream> request(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
        var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        connecting.add(pending);
        if (isPaused()) pending.cancel(true);
        try {
            return pending.get();
        } catch (CancellationException e) {
            if (isPaused()) return null;
            throw new IOException("Request cancelled", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("Request failed", e.getCause());
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } finally {
            connecting.remove(pending);
        }
    }

    private synchronized HttpClient client(TaskOptions options, List<Cookie> cookies) throws IOException {
        ClientKey key = new ClientKey(options, cookies);
        HttpClient existing = clients.get(key);
        if (existing != null) return existing;
        if (cookies == null) cookies = options.loadCookies();
        HttpClient next = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(network.timeoutSeconds()))
            .proxy(options.proxySelector()).cookieHandler(new TransferCookies(cookies))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
        clients.put(key, next);
        return next;
    }

    private void waitForHost(String host) throws InterruptedException {
        long wait;
        synchronized (nextRequest) {
            long now = System.nanoTime();
            long start = Math.max(nextRequest.getOrDefault(host, now), now);
            nextRequest.put(host, start + Duration.ofMillis(network.downloadDelayMs()).toNanos());
            wait = start - now;
        }
        while (wait > 0 && !isPaused()) {
            long step = Math.min(wait, TimeUnit.MILLISECONDS.toNanos(100));
            TimeUnit.NANOSECONDS.sleep(step);
            wait -= step;
        }
    }

    private boolean copy(InputStream body, Path part, long offset, long length) throws IOException {
        active.add(body);
        var lastByte = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        var timeout = timer.scheduleAtFixedRate(() -> {
            if (System.nanoTime() - lastByte.get() >= Duration.ofSeconds(network.timeoutSeconds()).toNanos()) closeBody(body);
        }, 1, 1, TimeUnit.SECONDS);
        try {
            try (var out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                    offset > 0 ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while (!isPaused() && (read = body.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    lastByte.set(System.nanoTime());
                }
            }
            if (isPaused()) return false;
            long size = Files.size(part);
            if (size == 0 || length >= 0 && size != length) throw new IOException("Incomplete download");
            return true;
        } catch (IOException e) {
            if (isPaused()) return false;
            throw e;
        } finally {
            timeout.cancel(false);
            active.remove(body);
        }
    }

    private static Resume validator(HttpResponse<?> response, String url, long length) {
        if (length <= 0) return null;
        String etag = response.headers().firstValue("ETag").orElse("");
        if (etag.startsWith("\"") && etag.endsWith("\"")) return new Resume(url, "ETag", etag, length);
        return response.headers().firstValue("Last-Modified")
            .map(value -> new Resume(url, "Last-Modified", value, length)).orElse(null);
    }

    private static Resume readResume(Path metadata, Path part, String url) {
        try {
            List<String> lines = Files.readAllLines(metadata);
            long size = Files.size(part);
            long length = Long.parseLong(lines.get(3));
            if (!url.equals(lines.get(0)) || size <= 0 || size >= length
                || !Set.of("ETag", "Last-Modified").contains(lines.get(1))) return null;
            return new Resume(url, lines.get(1), lines.get(2), length);
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static boolean matchesType(MediaItem item, String mime) {
        return switch (item.type()) {
            case "image", "video" -> mime.startsWith(item.type() + "/") || "application/octet-stream".equals(mime);
            case "file" -> !mime.isBlank() && !Set.of("text/html", "application/xhtml+xml").contains(mime);
            default -> false;
        };
    }

    private static String extension(URI uri, String mime) {
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/svg+xml" -> "svg";
            case "video/quicktime" -> "mov";
            case "application/pdf" -> "pdf";
            case "application/zip" -> "zip";
            default -> {
                var suffix = Pattern.compile("\\.([a-zA-Z0-9]{1,8})$").matcher(uri.getPath());
                if (suffix.find()) yield suffix.group(1).toLowerCase(Locale.ROOT);
                String subtype = mime.substring(mime.indexOf('/') + 1);
                yield mime.contains("/") && subtype.matches("[a-z0-9]{1,8}") ? subtype : "bin";
            }
        };
    }

    static String fileKey(MediaItem item) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(item.identity().getBytes(StandardCharsets.UTF_8));
            return item.type() + "_" + HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean findCompleted(Path folder, String key) throws IOException {
        if (!Files.isDirectory(folder)) return false;
        try (var matches = Files.newDirectoryStream(folder, key + ".*")) {
            for (Path path : matches) {
                if (!path.toString().endsWith(".part") && !path.toString().endsWith(".resume")
                    && Files.isRegularFile(path) && Files.size(path) > 0) return true;
            }
        }
        return false;
    }

    private static void closeBody(InputStream body) {
        try { body.close(); } catch (IOException ignored) {}
    }

    @Override public void close() {
        pause();
        workers.shutdownNow();
        timer.shutdownNow();
        clients.values().forEach(HttpClient::close);
    }
}
