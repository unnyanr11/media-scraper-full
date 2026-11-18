package io.github.unnyanr11.mediascraper;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import java.nio.file.Path;
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

    public void scan(String target, Path cookiesFile, Path downloadFolder, boolean updateOnly, Consumer<MediaItem> onItem, Consumer<String> status) {
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
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            BrowserContext context = browser.newContext();
            try {
                if (cookiesFile != null) {
                    var cookies = NetscapeCookies.load(cookiesFile);
                    context.addCookies(cookies);
                    status.accept("Imported " + cookies.size() + " cookies into isolated context");
                }
                Page page = context.newPage();
                page.setDefaultTimeout(10_000);
                status.accept("Opening webpage...");
                Response response = page.navigate(target, new Page.NavigateOptions().setTimeout(30_000));
                if (response != null && (response.status() == 401 || response.status() == 403 || response.status() == 429)) {
                    status.accept("Access denied or rate limited (" + response.status() + "); scan stopped");
                    return;
                }
                try { page.waitForLoadState(LoadState.DOMCONTENTLOADED); } catch (PlaywrightException ignored) {}
                int idle = 0;
                int lastCount = -1;
                for (int scroll = 0; scroll < 80 && !stopped.get(); scroll++) {
                    collect(page, context, downloadFolder, store, target, updateOnly, onItem, status);
                    if (items.size() == lastCount) idle++; else idle = 0;
                    lastCount = items.size();
                    status.accept("Scroll " + scroll + " | Discovered " + items.size() + " links");
                    if (idle >= 6) break;
                    // A single click can expose carousel slides that are absent from the initial DOM.
                    try {
                        Locator next = page.locator("[aria-label='Next'], [aria-label='Next slide'], button.swiper-button-next");
                        if (next.count() > 0 && next.first().isVisible()) {
                            next.first().click(new Locator.ClickOptions().setTimeout(1500));
                            collect(page, context, downloadFolder, store, target, updateOnly, onItem, status);
                        }
                    } catch (PlaywrightException ignored) {}
                    page.evaluate("() => window.scrollBy(0, Math.max(500, window.innerHeight * .85))");
                    page.waitForTimeout(1100);
                }
                status.accept(stopped.get() ? "Stopped" : "Scan completed: " + items.size() + " links; " + downloaded + " files downloaded");
            } finally { context.close(); }
        } catch (Exception e) {
            status.accept("Scan failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void collect(Page page, BrowserContext context, Path folder, ScanStore store, String source, boolean updateOnly, Consumer<MediaItem> consumer, Consumer<String> status) {
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
