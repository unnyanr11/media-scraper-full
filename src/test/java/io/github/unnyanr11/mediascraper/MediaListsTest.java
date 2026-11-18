package io.github.unnyanr11.mediascraper;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MediaListsTest {
    @TempDir Path folder;

    @Test void parsesRssAndAtomEnclosuresAndPreservesMetadata() {
        var rss = MediaLists.feed("""
            <rss><channel><item><title>Episode 2</title><guid>ep2</guid><pubDate>Wed, 18 Nov 2026</pubDate>
            <enclosure url="/episodes/two.mp3" type="audio/mpeg"/></item></channel></rss>
            """, "https://example.org/rss");
        assertEquals(1, rss.size());
        assertEquals("https://example.org/episodes/two.mp3", rss.getFirst().url());
        assertEquals("Episode 2", rss.getFirst().caption());
        assertEquals("Wed, 18 Nov 2026", rss.getFirst().publishedAt());
        assertEquals("https://example.org/rss#ep2", rss.getFirst().postId());

        var atom = MediaLists.feed("""
            <feed xmlns="http://www.w3.org/2005/Atom"><entry><id>x1</id>
            <link rel="alternate" href="/playlist"/><link rel="enclosure" href="video.m3u8"/></entry></feed>
            """, "https://example.org/show/feed.xml");
        assertEquals("https://example.org/playlist", atom.getFirst().postUrl());
        assertEquals("hls", atom.getFirst().type());
    }

    @Test void restrictsOpenDirectoriesToTheirSubtreeAndFindsExtensionlessFiles() {
        var directory = MediaLists.directory("""
            <a href="../">Parent</a><a href="?C=N;O=D">Sort</a><a href="subfolder/">Child</a>
            <a href="DATASET">File</a><a href="cover.jpg">Image</a><a href="/other/intruder">Outside</a>
            <a href="https://other.example/files/remote">Remote</a>
            """, "https://example.org/files/");
        assertEquals(List.of("https://example.org/files/subfolder/"), directory.folders());
        assertEquals(List.of("file", "image"), directory.files().stream().map(MediaItem::type).toList());
        assertEquals("https://example.org/files/DATASET", directory.files().getFirst().url());
    }

    @Test void streamsHlsUsingTheHighestMultiplexedRenditionAndSavesInOrder() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            Map<String, String> files = Map.of(
                "/source.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nlow.m3u8\n"
                    + "#EXT-X-STREAM-INF:BANDWIDTH=800\nhigh.m3u8\n",
                "/high.m3u8", "#EXTM3U\n#EXTINF:1,\n1.ts\n#EXTINF:1,\n2.ts\n#EXT-X-ENDLIST\n",
                "/1.ts", "first", "/2.ts", "second");
            server.createContext("/", exchange -> {
                try (exchange) {
                    String value = files.get(exchange.getRequestURI().getPath());
                    if (value == null || !"integration-test".equals(exchange.getRequestHeaders().getFirst("X-Client"))) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    byte[] data = value.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                }
            });
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/source.m3u8";
            Path database = folder.resolve("history.sqlite");
            var settings = new ScanService.Settings(folder, true, false, false, 1, "", Set.of(), 1);
            var network = new NetworkSettings(1, 1, 5, 0, "", Map.of("X-Client", "integration-test"));
            List<MediaItem> found = new ArrayList<>();
            List<String> messages = new ArrayList<>();
            new ScanService(database, Map.of(), network).scan(url, new TaskOptions(null, "", null, network),
                settings, found::add, messages::add);
            assertEquals("hls", found.getFirst().type());
            assertEquals("firstsecond", Files.readString(folder.resolve(DownloadQueue.fileKey(found.getFirst()) + ".ts")),
                messages::toString);
            assertThrows(java.io.IOException.class, () -> HlsPlaylist.parse(
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:1,\n1.ts\n#EXT-X-ENDLIST\n", url));
        } finally {
            server.stop(0);
        }
    }

    @Test void remembersCategorizedSourcesAndTheirScanModes() throws Exception {
        try (var store = new ScanStore(folder.resolve("categories.sqlite"))) {
            String url = "https://example.org/feed";
            store.saveSource(url, "Podcasts", ScanService.Mode.FEED);
            assertEquals(List.of(new ScanStore.SavedSource(url, "Podcasts", ScanService.Mode.FEED)), store.savedSources());
            store.saveMode(url, ScanService.Mode.AUTO);
            assertEquals("Podcasts", store.savedSources().getFirst().category());
        }
    }
}
