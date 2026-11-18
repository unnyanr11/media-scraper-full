package io.github.unnyanr11.mediascraper;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ScanService {
    private final AtomicBoolean stopped = new AtomicBoolean();
    private int downloaded = 0;
    private final Set<String> seen = new LinkedHashSet<>();
    private final List<MediaItem> items = new ArrayList<>();

    public void stop() { stopped.set(true); }
    public boolean isStopped() { return stopped.get(); }

    public void scan(String target, Path cookiesFile, Path downloadFolder, boolean updateOnly, int maxPages, String urlFilter, Consumer<MediaItem> onItem, Consumer<String> status) {
        stopped.set(false);
        seen.clear();
        items.clear();
        downloaded = 0;
        URI uri = URI.create(target);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Enter a valid http(s) URL");
        }
        try (ScanStore store = new ScanStore();
             Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(browserOptions())) {
            BrowserContext context = browser.newContext();
            try {
                if (cookiesFile != null) {
                    var cookies = NetscapeCookies.load(cookiesFile);
                    context.addCookies(cookies);
                    status.accept("Imported " + cookies.size() + " cookies into isolated context");
                }
                // Handle direct image/video links (including generated sequences) without page scraping.
                String lowerPath = uri.getPath().toLowerCase(java.util.Locale.ROOT);
                String directType = lowerPath.matches(".*\\.(jpg|jpeg|png|gif|webp|avif)")
                    ? "image" : (lowerPath.matches(".*\\.(mp4|webm|mov)") ? "video" : null);
                if (directType != null) {
                    MediaItem item = new MediaItem(target, target, directType);
                    boolean fresh = store.record(target, item);
                    if (!updateOnly || fresh) onItem.accept(item);
                    if (downloadFolder != null && (!updateOnly || fresh) && !store.downloaded(target, item)) {
                        if (MediaDownloader.download(context, item, downloadFolder, 1, stopped, status)) {
                            store.markDownloaded(target, item);
                            downloaded++;
                        }
                    }
                    status.accept("Direct media: " + (downloaded > 0 ? "downloaded" : "discovered"));
                    return;
                }
                Page page = context.newPage();
                page.setDefaultTimeout(10_000);
                Deque<String> queue = new ArrayDeque<>();
                Set<String> visited = new HashSet<>();
                queue.add(target);
                int pages = 0;
                String host = uri.getHost().toLowerCase(Locale.ROOT);
                while (!queue.isEmpty() && !stopped.get() && pages < Math.max(1, Math.min(50, maxPages))) {
                    String pageUrl = queue.removeFirst();
                    if (!visited.add(pageUrl)) continue;
                    pages++;
                    status.accept("Scanning page " + pages + ": " + pageUrl);
                    Response response;
                    try {
                        response = page.navigate(pageUrl, new Page.NavigateOptions().setTimeout(30_000));
                    } catch (PlaywrightException ex) {
                        status.accept("Skipped inaccessible page: " + pageUrl);
                        continue;
                    }
                    if (response != null && (response.status() == 401 || response.status() == 403
                        || response.status() == 429)) {
                        status.accept("Restricted or rate limited (" + response.status() + "); scan stopped");
                        stopped.set(true);
                        break;
                    }
                    try { page.waitForLoadState(LoadState.DOMCONTENTLOADED); }
                    catch (PlaywrightException ignored) {}
                    int idle = 0;
                    int lastCount = -1;
                    int limit = pages == 1 ? 80 : 12;
                    for (int scroll = 0; scroll < limit && !stopped.get(); scroll++) {
                        collect(page, context, downloadFolder, store, target, updateOnly, urlFilter, onItem, status);
                        if (items.size() == lastCount) idle++; else idle = 0;
                        lastCount = items.size();
                        if (idle >= 6) break;
                        try {
                            Locator next = page.locator("[aria-label='Next'], [aria-label='Next slide'], button.swiper-button-next");
                            if (next.count() > 0 && next.first().isVisible()) {
                                next.first().click(new Locator.ClickOptions().setTimeout(1500));
                                collect(page, context, downloadFolder, store, target, updateOnly, urlFilter, onItem, status);
                            }
                        } catch (PlaywrightException ignored) {}
                        page.evaluate("() => window.scrollBy(0, Math.max(500, window.innerHeight * .85))");
                        page.waitForTimeout(1100);
                    }
                    // Follow only same-host HTML pages, at a bounded depth and count.
                    if (maxPages > 1) {
                        for (MediaItem link : items) {
                            if (!"page".equals(link.type()) || visited.contains(link.url())) continue;
                            try {
                                URI linked = URI.create(link.url());
                                if (!host.equalsIgnoreCase(linked.getHost())) continue;
                                if (!Set.of("http", "https").contains(linked.getScheme())) continue;
                                if (linked.getPath().matches("(?i).*\\.(zip|exe|pdf|png|jpg|jpeg|gif|webp|mp4|mp3|webm)$")) continue;
                                if (!queue.contains(link.url()) && queue.size() < 100) queue.addLast(link.url());
                            } catch (IllegalArgumentException ignored) {}
                        }
                    }
                }
                status.accept(stopped.get() ? "Stopped" : "Scan completed: " + pages + " pages, " + items.size() + " links; " + downloaded + " files downloaded");
            } finally { context.close(); }
        } catch (Exception e) {
            status.accept("Scan failed: " + e.getMessage());
        }
    }

    private static BrowserType.LaunchOptions browserOptions() {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
        // jpackage app image: <root>/runtime and <root>/app/browsers.
        Path runtime = Path.of(System.getProperty("java.home"));
        Path root = runtime.getParent();
        if (root == null) return options;
        Path browsers = root.resolve("app").resolve("browsers");
        if (!Files.isDirectory(brows)) return options;
        try (var files = Files.walk(brows, 6)) {
            var chrome = files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equalsIgnoreCase("chrome.exe"))
                .findFirst();
            chrome.ifPresent(options::setExecutablePath);
        } catch (IOException ignored) {}
        return options;
    }

    @SuppressWarnings("unchecked")
    private void collect(Page page, BrowserContext context, Path folder, ScanStore store, String source, boolean updateOnly, String urlFilter, Consumer<MediaItem> consumer, Consumer<String> status) {
        Object result = page.evaluate("""
          () => {
            const found = [];
            const add = (url, type) => {
              if (!url) return;
              try {
                const u = new URL(url, document.baseURI);
                if (!['http:', 'https:'].includes(u.protocol)) return;
                found.push({url: u.href, type});
              } catch {}
            };
            for (const el of document.querySelectorAll('img')) {
              add(el.currentSrc || el.src, 'image');
              if (el.srcset) for (const src of el.srcset.split(',')) add(src.trim().split(/\\s+/)[0], 'image');
            }
            for (const el of document.querySelectorAll('video, video source')) add(el.currentSrc || el.src, 'video');
            for (const el of document.querySelectorAll('meta[property="og:image"], meta[property="og:video"]'))
              add(el.content, el.getAttribute('property') === 'og:image' ? 'image' : 'video');
            for (const el of document.querySelectorAll('a[href]')) {
              const href = el.href;
              if (/\\.(png|jpe?g|gif|webp|avif)(\\?|#|$)/i.test(href)) add(href, 'image');
              else if (/\\.(mp4|webm|mov)(\\?|#|$)/i.test(href)) add(href, 'video');
              else add(href, 'page');
            }
            return found;
          }
          """);
        if (!(result instanceof List<?> list)) return;
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> map)) continue;
            String url = String.valueOf(map.get("url"));
            String type = String.valueOf(map.get("type"));
            if (urlFilter != null && !urlFilter.isBlank() && !url.toLowerCase(Locale.ROOT).contains(urlFilter.toLowerCase(Locale.ROOT))) continue;
            if (seen.add(type + "|" + url)) {
                MediaItem item = new MediaItem(page.url(), url, type);
                items.add(item);
                try {
                    boolean isNew = store.record(source, item);
                    if (!updateOnly || isNew) consumer.accept(item);
                    if (folder != null && (type.equals("image") || type.equals("video"))
                            && (!updateOnly || isNew) && !store.downloaded(source, item)) {
                        if (MediaDownloader.download(context, item, folder, items.size(), stopped, status)) {
                            store.markDownloaded(source, item);
                            downloaded++;
                        }
                    }
                } catch (java.sql.SQLException ex) {
                    stopped.set(true);
                    status.accept("History database error; stopping: " + ex.getMessage());
                }
            }
        }
    }
}
