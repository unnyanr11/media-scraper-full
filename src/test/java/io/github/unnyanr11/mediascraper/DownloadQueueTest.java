package io.github.unnyanr11.mediascraper;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DownloadQueueTest {
    @TempDir Path workspace;
    private HttpServer server;

    @AfterEach void stopServer() {
        if (server != null) server.stop(0);
    }
    private static final TaskOptions PUBLIC = new TaskOptions(null, "", null);

    @Test void resumesPersistedBytesAndMarksHistoryAfterSaving() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var range = new AtomicReference<String>();
        var validator = new AtomicReference<String>();
        server.createContext("/picture.png", exchange -> {
            try (exchange) {
                range.set(exchange.getRequestHeaders().getFirst("Range"));
                validator.set(exchange.getRequestHeaders().getFirst("If-Range"));
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.getResponseHeaders().set("ETag", "\"v1\"");
                exchange.getResponseHeaders().set("Content-Range", "bytes 4-7/8");
                exchange.sendResponseHeaders(206, 4);
                exchange.getResponseBody().write(new byte[] {5, 6, 7, 8});
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/picture.png";
        var item = new MediaItem(url, url, "image");
        Path folder = Files.createDirectory(workspace.resolve("files"));
        Path database = workspace.resolve("history.sqlite");
        String key = DownloadQueue.fileKey(item);
        Files.write(folder.resolve(key + ".part"), new byte[] {1, 2, 3, 4});
        Files.write(folder.resolve(key + ".resume"), List.of(url, "ETag", "\"v1\"", "8"));
        try (ScanStore store = new ScanStore(database)) {
            store.record(url, item);
            store.enqueue(new ScanStore.Transfer(url, item, folder, PUBLIC));
        }
        try (ScanStore store = new ScanStore(database); var queue = new DownloadQueue(store, 4, message -> {})) {
            assertEquals(1, queue.download(store.pending(null, null), null, null));
            assertEquals("bytes=4-", range.get());
            assertEquals("\"v1\"", validator.get());
            assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, Files.readAllBytes(folder.resolve(key + ".png")));
            assertTrue(store.pending(null, null).isEmpty());
            assertTrue(store.historyEntries(url).getFirst().downloaded());
        }
    }

    @Test void replacesPartialWhenRangeIsIgnoredAndPreservesRestrictedWork() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var hits = new AtomicInteger();
        server.createContext("/range.png", exchange -> {
            try (exchange) {
                hits.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, 3);
                exchange.getResponseBody().write(new byte[] {7, 8, 9});
            }
        });
        server.createContext("/restricted.png", exchange -> {
            try (exchange) { exchange.sendResponseHeaders(429, -1); }
        });
        server.start();
        String host = "http://127.0.0.1:" + server.getAddress().getPort();
        var media = new MediaItem(host, host + "/range.png", "image");
        var restricted = new MediaItem(host, host + "/restricted.png", "image");
        String key = DownloadQueue.fileKey(media);
        Files.write(workspace.resolve(key + ".part"), new byte[] {1});
        Files.write(workspace.resolve(key + ".resume"), List.of(media.url(), "ETag", "\"old\"", "3"));
        try (var store = new ScanStore(workspace.resolve("db.sqlite")); var queue = new DownloadQueue(store, 1, message -> {})) {
            for (var item : List.of(media, restricted)) {
                store.record(host, item);
                store.enqueue(new ScanStore.Transfer(host, item, workspace, PUBLIC));
            }
            assertEquals(1, queue.download(store.pending(null, null), null, null));
            assertEquals(1, hits.get());
            assertArrayEquals(new byte[] {7, 8, 9}, Files.readAllBytes(workspace.resolve(key + ".png")));
            assertEquals(restricted, store.pending(null, null).getFirst().item());
        }
    }

    @Test void pauseClosesActiveTransferAndNextRunResumesIt() throws Exception {
        byte[] bytes = new byte[160_000];
        new Random(17).nextBytes(bytes);
        var firstChunk = new CountDownLatch(1);
        var resumeRange = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (ExecutorService serverThreads = Executors.newVirtualThreadPerTaskExecutor();
             ExecutorService clientThread = Executors.newSingleThreadExecutor()) {
            server.setExecutor(serverThreads);
            server.createContext("/media.png", exchange -> {
                try (exchange) {
                    var headers = exchange.getResponseHeaders();
                    headers.set("Content-Type", "image/png");
                    headers.set("ETag", "\"v1\"");
                    String range = exchange.getRequestHeaders().getFirst("Range");
                    if (range == null) {
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes, 0, 100_000);
                        exchange.getResponseBody().flush();
                        firstChunk.countDown();
                        Thread.sleep(2_000);
                    } else {
                        resumeRange.set(range);
                        int offset = Integer.parseInt(range.substring(6, range.length() - 1));
                        headers.set("Content-Range", "bytes " + offset + "-" + (bytes.length - 1) + "/" + bytes.length);
                        exchange.sendResponseHeaders(206, bytes.length - offset);
                        exchange.getResponseBody().write(bytes, offset, bytes.length - offset);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/media.png";
            MediaItem item = new MediaItem(url, url, "image");
            Path folder = Files.createDirectory(workspace.resolve("images"));
            try (var store = new ScanStore(workspace.resolve("pause.sqlite"))) {
                store.record(url, item);
                store.enqueue(new ScanStore.Transfer(url, item, folder, PUBLIC));
                Path part = folder.resolve(DownloadQueue.fileKey(item) + ".part");
                try (var queue = new DownloadQueue(store, 1, ignored -> {})) {
                    var result = clientThread.submit(() -> queue.download(store.pending(null, null), null, null));
                    assertTrue(firstChunk.await(5, TimeUnit.SECONDS));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while ((!Files.exists(part) || Files.size(part) == 0) && System.nanoTime() < deadline) Thread.sleep(10);
                    assertTrue(Files.size(part) > 0);
                    queue.pause();
                    assertEquals(0, result.get(5, TimeUnit.SECONDS));
                    assertEquals(1, store.pending(null, null).size());
                }
                try (var queue = new DownloadQueue(store, 1, ignored -> {})) {
                    assertEquals(1, queue.download(store.pending(null, null), null, null));
                    assertNotNull(resumeRange.get());
                    assertArrayEquals(bytes, Files.readAllBytes(folder.resolve(DownloadQueue.fileKey(item) + ".png")));
                }
            }
        }
    }

    @Test void pendingPastedCookiesStayInMemoryAndCanBeReenteredAfterRestart() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var hits = new AtomicInteger();
        var received = new AtomicReference<String>();
        server.createContext("/protected.png", exchange -> {
            try (exchange) {
                hits.incrementAndGet();
                String cookie = exchange.getRequestHeaders().getFirst("Cookie");
                received.set(cookie);
                if (!"sessionid=dummy%3Afresh".equals(cookie)) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, 1);
                exchange.getResponseBody().write(9);
            }
        });
        server.start();
        String source = "http://127.0.0.1:" + server.getAddress().getPort() + "/protected.png";
        Path database = workspace.resolve("cookies.sqlite");
        Path folder = workspace.resolve("protected");
        MediaItem item = new MediaItem(source, source, "image", source, "", "", source);
        var previous = CookieInput.paste("127.0.0.1\tFALSE\t/\tFALSE\t0\tsessionid\tdummy%3Aprevious");
        try (var store = new ScanStore(database); var queue = new DownloadQueue(store, 1, ignored -> {})) {
            store.record(source, item);
            store.enqueue(new ScanStore.Transfer(source, item, folder, new TaskOptions(previous, "", null)));
            assertFalse(store.pending(null, null, Map.of(previous.reference(), previous)).getFirst().options().needsPastedCookies());
            assertTrue(store.pending(null, null).getFirst().options().needsPastedCookies());
            assertEquals(0, queue.download(store.pending(null, null), null, null));
            assertEquals(0, hits.get());
            store.enqueue(new ScanStore.Transfer(source, item, folder, PUBLIC));
            assertTrue(store.pending(null, null).getFirst().options().needsPastedCookies());
            try (var sql = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = sql.createStatement(); var rows = statement.executeQuery("SELECT cookies FROM transfers")) {
                assertTrue(rows.next());
                assertEquals(previous.reference(), rows.getString(1));
            }
        }
        var fresh = CookieInput.paste("127.0.0.1\tFALSE\t/\tFALSE\t0\tsessionid\tdummy%3Afresh");
        var options = new TaskOptions(fresh, "", null);
        var settings = new ScanService.Settings(folder, true, false, true, 1, "", Set.of(), 1);
        new ScanService(database, Map.of(fresh.reference(), fresh)).scan(source, options, settings, ignored -> {}, ignored -> {});
        assertEquals("sessionid=dummy%3Afresh", received.get());
        try (var store = new ScanStore(database)) {
            assertTrue(store.pending(null, null).isEmpty());
            assertTrue(store.downloaded(source, item));
        }
    }
}
