package io.github.unnyanr11.mediascraper;

import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BrowserFixturesTest {
    @TempDir Path workspace;
    private static final TaskOptions PUBLIC = new TaskOptions(null, "", null);
    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll static void openBrowser() {
        Assumptions.assumeTrue(System.getProperty("media.chromium.path") != null || System.getenv("MEDIA_CHROMIUM_PATH") != null);
        playwright = ScanService.playwright();
        browser = ScanService.browser(playwright, PUBLIC);
    }

    @AfterAll static void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @Test void selectsBestImagesAndExtractsAllCarouselAssetsFromStructuredData() throws Exception {
        String address = "https://www.instagram.com/p/sample/";
        try (var page = fixture(address, """
            <article><h1>Carousel caption</h1><a href="/p/sample/">Post</a><time datetime="2026-01-02"></time>
            <img alt="Gallery" src="https://static.example/small.jpg"
              srcset="https://static.example/small.jpg 100w, https://static.example/original.jpg 1600w">
            <i class="full" data-url="https://static.example/extra"></i></article>
            <script type="application/json">{"edge_sidecar_to_children":{"edges":[
            {"node":{"display_url":"https://static.example/slide-2.png"}},
            {"node":{"is_video":true,"video_url":"https://static.example/slide-3.mp4"}}]}}</script>
            """)) {
            Path script = workspace.resolve("extract.txt");
            Files.writeString(script, "extract=image || .full || data-url\n");
            var assets = PageExtractor.extract(page, SiteRules.load(script));
            assertEquals(Set.of("https://static.example/original.jpg", "https://static.example/slide-2.png",
                "https://static.example/slide-3.mp4", "https://static.example/extra"),
                Set.copyOf(assets.items().stream().filter(MediaItem::downloadable).map(MediaItem::url).toList()));
            assertEquals("Carousel caption", assets.items().stream().filter(item -> item.type().equals("post")).findFirst().orElseThrow().caption());
        }
    }

    @Test void extractsOriginalsFromGoogleBingAndYandex() {
        var fixtures = Map.of(
            "https://www.google.com/search?udm=2", "<a href='/imgres?imgurl=https%3A%2F%2Fsite.example%2Foriginal.jpg&amp;imgrefurl=https%3A%2F%2Fsite.example%2Fstory'>Image</a>",
            "https://www.bing.com/images/search?q=example", "<a m='{\"murl\":\"https://site.example/original.jpg\",\"purl\":\"https://site.example/story\"}'>Image</a>",
            "https://yandex.com/images/search?text=example", "<a data-bem='{\"serp-item\":{\"img_href\":\"https://site.example/original.jpg\",\"snippet\":{\"url\":\"https://site.example/story\"}}}'>Image</a>");
        for (var site : fixtures.entrySet()) {
            try (var page = fixture(site.getKey(), site.getValue() + "<img src='https://site.example/thumb.jpg'>")) {
                var assets = PageExtractor.extract(page, SiteRules.empty()).items();
                assertEquals(1, assets.size(), site.getKey());
                assertEquals("https://site.example/original.jpg", assets.getFirst().url(), site.getKey());
                assertEquals("https://site.example/story", assets.getFirst().postUrl(), site.getKey());
            }
        }
    }

    @Test void normalizesPlatformMediaAndIgnoresCipheredYoutubeFormats() {
        var fixtures = Map.of(
            "https://x.com/user/status/1", new String[]{
                "<article data-testid='tweet'><a href='/user/status/1'>Date</a><div data-testid='tweetPhoto'><img src='https://pbs.twimg.com/media/image?format=jpg&amp;name=small'></div></article>",
                "https://pbs.twimg.com/media/image?format=jpg&name=orig"},
            "https://www.pinterest.com/pin/123/", new String[]{
                "<section data-test-id='pin'><img src='https://i.pinimg.com/236x/aa/image.jpg'></section>",
                "https://i.pinimg.com/originals/aa/image.jpg"},
            "https://www.reddit.com/r/example/comments/1/name/", new String[]{
                "<shreddit-post><img src='https://preview.redd.it/example.jpg?width=640'></shreddit-post>",
                "https://i.redd.it/example.jpg"},
            "https://www.youtube.com/watch?v=abc", new String[]{
                """
                <script>var ytInitialPlayerResponse = {"streamingData":{"formats":[
                {"url":"https://media.example/direct.mp4","height":720,"mimeType":"video/mp4"},
                {"signatureCipher":"s=opaque","height":1080,"mimeType":"video/mp4"}]}};</script>
                """, "https://media.example/direct.mp4"},
            "https://imgur.com/a/example", new String[]{
                "<article><img data-original='https://i.imgur.com/render.png' src='/thumb.png'></article>",
                "https://i.imgur.com/render.png"},
            "https://www.deviantart.com/a/art/example-1", new String[]{
                "<article><a href='https://images.example/full.png'><img src='/tiny.png'></a></article>",
                "https://images.example/full.png"},
            "https://bsky.app/profile/example/post/1", new String[]{
                "<article data-testid='feedItem-by-example'><img src='https://cdn.bsky.app/img/feed_thumbnail/example'></article>",
                "https://cdn.bsky.app/img/feed_fullsize/example"});
        for (var site : fixtures.entrySet()) {
            try (var page = fixture(site.getKey(), site.getValue()[0])) {
                var actual = PageExtractor.extract(page, SiteRules.empty()).items().stream().filter(MediaItem::downloadable)
                    .map(MediaItem::url).toList();
                assertTrue(actual.contains(site.getValue()[1]), () -> site.getKey() + " produced " + actual);
            }
        }
    }

    @Test void refreshesExpiredLinkThroughOriginalPostAndSavesMedia() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/post", exchange -> {
                try (exchange) {
                    byte[] html = "<img src='/asset.png?sig=new'>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html");
                    exchange.sendResponseHeaders(200, html.length);
                    exchange.getResponseBody().write(html);
                }
            });
            server.createContext("/asset.png", exchange -> {
                try (exchange) {
                    if (!"sig=new".equals(exchange.getRequestURI().getQuery())) {
                        exchange.sendResponseHeaders(403, -1);
                    } else {
                        exchange.getResponseHeaders().set("Content-Type", "image/png");
                        exchange.sendResponseHeaders(200, 3);
                        exchange.getResponseBody().write(new byte[] {4, 5, 6});
                    }
                }
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            String post = base + "/post";
            String asset = base + "/asset.png";
            var item = new MediaItem(post, asset + "?sig=old", "image", post, "", "", asset);
            try (var store = new ScanStore(workspace.resolve("refresh.sqlite")); var queue = new DownloadQueue(store, 1, message -> {})) {
                store.record(post, item);
                store.enqueue(new ScanStore.Transfer(post, item, workspace, PUBLIC));
                assertEquals(1, queue.download(store.pending(null, null), null, null));
                assertEquals(asset + "?sig=new", store.history(post).getFirst().url());
                assertTrue(store.historyEntries(post).getFirst().downloaded());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test void forumArchiveLinksPagesAndRemovesActiveMarkup() throws Exception {
        String base = "https://forums.example/thread";
        var archive = new HtmlArchive(workspace, base);
        try (var page = fixture(base,
                "<title>Thread one</title><article><p onclick='alert(1)'>First</p><a rel='next' href='/thread?page=2'>Next</a></article><script>0</script>")) {
            archive.save(page, base, List.of());
        }
        try (var page = fixture(base + "?page=2", "<title>Thread two</title><article>Second</article>")) {
            archive.save(page, base + "?page=2", List.of());
        }
        archive.index();
        try (var paths = Files.walk(workspace)) {
            var text = new ArrayList<String>();
            for (Path file : paths.filter(path -> path.toString().endsWith(".html")).toList()) text.add(Files.readString(file));
            assertEquals(3, text.size());
            String first = text.stream().filter(html -> html.contains("<title>Thread one</title>")).findFirst().orElseThrow();
            assertTrue(first.matches("(?s).*href=\"page_[a-f0-9]{32}\\.html\">Next</a>.*"), first);
            assertFalse(first.contains("<script>"));
            assertFalse(first.contains("onclick"));
        }
    }

    @Test void scansDownloadsArchivesAndContinuesTheSavedCrawl() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/thread", exchange -> {
                try (exchange) {
                    boolean second = "page=2".equals(exchange.getRequestURI().getQuery());
                    String content = second ? "<title>Second</title><img src='/second.png'>"
                        : "<title>First</title><img src='/first.png'><a rel='next' href='/thread?page=2'>Next</a>";
                    byte[] html = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html");
                    exchange.sendResponseHeaders(200, html.length);
                    exchange.getResponseBody().write(html);
                }
            });
            for (String image : List.of("/first.png", "/second.png")) {
                server.createContext(image, exchange -> {
                    try (exchange) {
                        exchange.getResponseHeaders().set("Content-Type", "image/png");
                        exchange.sendResponseHeaders(200, 3);
                        exchange.getResponseBody().write(new byte[] {1, 2, 3});
                    }
                });
            }
            server.start();
            String source = "http://127.0.0.1:" + server.getAddress().getPort() + "/thread";
            Path history = workspace.resolve("scan.sqlite");
            Path downloads = workspace.resolve("downloads");
            var settings = new ScanService.Settings(downloads, true, true, false, 1, "", Set.of("png"), 2);
            var found = new ArrayList<MediaItem>();
            var messages = new ArrayList<String>();
            new ScanService(history).scan(source, PUBLIC, settings, found::add, messages::add);
            assertTrue(found.stream().anyMatch(item -> item.url().endsWith("/first.png")), () -> messages.toString());
            try (var store = new ScanStore(history)) {
                assertEquals(List.of(source + "?page=2"), store.checkpoint(source).pending());
            }
            found.clear();
            new ScanService(history).scan(source, PUBLIC, settings, found::add, messages::add);
            assertTrue(found.stream().anyMatch(item -> item.url().endsWith("/second.png")), () -> messages.toString());
            try (var store = new ScanStore(history)) {
                assertTrue(store.checkpoint(source).pending().isEmpty());
                assertEquals(2, store.historyEntries(source).stream().filter(ScanStore.HistoryEntry::downloaded).count());
            }
            try (var files = Files.walk(downloads)) {
                var saved = files.toList();
                assertEquals(2, saved.stream().filter(file -> file.toString().endsWith(".png")).count());
                assertTrue(saved.stream().anyMatch(file -> file.toString().endsWith("index.html")));
            }
        } finally {
            server.stop(0);
        }
    }

    private Page fixture(String address, String html) {
        Page page = browser.newPage();
        page.route("**/*", route -> route.fulfill(new Route.FulfillOptions().setStatus(200)
            .setContentType("text/html; charset=utf-8").setBody(route.request().isNavigationRequest() ? html : "")));
        page.navigate(address);
        return page;
    }
}
