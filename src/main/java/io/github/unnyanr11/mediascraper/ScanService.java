package io.github.unnyanr11.mediascraper;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class ScanService {
    private static final Set<Integer> RESTRICTED = Set.of(401, 403, 429);
    private static final int MAX_FRONTIER = 2_000;
    private static final int MAX_CHECKPOINT_VISITED = 20_000;
    private static final Pattern IMAGE = Pattern.compile("(?i)\\.(jpe?g|png|gif|webp|avif|svg|bmp|tiff?|ico|heic|jxl)$");
    private static final Pattern VIDEO = Pattern.compile("(?i)\\.(mp4|webm|mov|m4v|ogv|mkv)$");
    private static final Pattern FILE = Pattern.compile("(?i)\\.(pdf|zip|rar|7z|gz|tgz|tar|mp3|flac|wav|ogg|m4a|aac|epub|csv|txt|docx?|xlsx?|pptx?|exe|msi)$");

    record Settings(Path folder, boolean download, boolean archive, boolean updateOnly, int maxPages,
                    String keyword, Set<String> extensions, int workers, Set<String> mediaTypes, Mode mode) {
        Settings(Path folder, boolean download, boolean archive, boolean updateOnly, int maxPages,
                 String keyword, Set<String> extensions, int workers) {
            this(folder, download, archive, updateOnly, maxPages, keyword, extensions, workers, Set.of(), Mode.AUTO);
        }

        Settings withMode(Mode replacement) {
            return new Settings(folder, download, archive, updateOnly, maxPages, keyword, extensions,
                workers, mediaTypes, replacement);
        }

        static Set<String> extensions(String value) {
            Set<String> result = new HashSet<>();
            for (String extension : value.toLowerCase(Locale.ROOT).split(",")) {
                extension = extension.trim().replaceFirst("^\\.", "");
                if (!extension.isEmpty()) result.add(extension);
            }
            return Set.copyOf(result);
        }
    }

    enum Mode {
        AUTO("Website or profile"), FEED("RSS / Atom / podcast"), DIRECTORY("Open directory"),
        DIRECT("Direct file URL");
        private final String label;
        Mode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean downloadsPaused = new AtomicBoolean();
    private volatile DownloadQueue transfers;
    private final Path history;
    private final Map<String, CookieInput> pasted;
    private final NetworkSettings network;

    public ScanService() { this(null); }

    ScanService(Path history) { this(history, Map.of()); }

    ScanService(Path history, Map<String, CookieInput> pasted) {
        this(history, pasted, NetworkSettings.DEFAULT);
    }

    ScanService(Path history, Map<String, CookieInput> pasted, NetworkSettings network) {
        this.history = history;
        this.pasted = Map.copyOf(pasted);
        this.network = network;
    }

    private ScanStore openStore() throws SQLException {
        return history == null ? new ScanStore() : new ScanStore(history);
    }

    public void stop() {
        stopped.set(true);
        pauseDownloads();
    }

    public boolean isStopped() { return stopped.get(); }

    void pauseDownloads() {
        downloadsPaused.set(true);
        DownloadQueue queue = transfers;
        if (queue != null) queue.pause();
    }

    void scan(String target, TaskOptions options, Settings settings, Consumer<MediaItem> onItem, Consumer<String> status) {
        if (isStopped()) return;
        try (ScanStore store = openStore()) {
            URI uri = webUri(target);
            store.saveMode(target, settings.mode);
            SiteRules rules = options.rules() == null ? SiteRules.empty() : SiteRules.load(options.rules());
            Session session = new Session(target, options, settings, rules, store, onItem);
            String direct = directType(uri);
            if (direct == null && settings.mode == Mode.DIRECT) direct = "file";
            List<Cookie> cookies = null;
            int pages = 0;
            if (direct != null && settings.mode != Mode.FEED && settings.mode != Mode.DIRECTORY) {
                session.accept(new MediaItem(target, target, direct, target, "", "", MediaItem.stableUrl(target)));
            } else {
                try (Playwright playwright = playwright();
                     Browser browser = browser(playwright, options);
                     BrowserContext context = context(browser, options)) {
                    pages = settings.mode == Mode.FEED ? scanFeed(context, session) : crawl(uri, context, session, status);
                    cookies = context.cookies();
                }
            }
            if (isStopped()) return;
            int downloaded = 0;
            if (settings.download) {
                var jobs = store.pending(target, settings.folder, pasted);
                if (options.cookies() != null) {
                    // An explicit new session for this source can reauthenticate jobs from before restart.
                    for (var job : jobs) {
                        if (!job.options().needsPastedCookies()) continue;
                        var restored = new TaskOptions(options.cookies(), job.options().proxy(), job.options().rules(), network);
                        store.enqueue(new ScanStore.Transfer(job.source(), job.item(), job.folder(), restored));
                    }
                    jobs = store.pending(target, settings.folder, pasted);
                }
                downloaded = runTransfers(store, jobs, options, cookies, settings.workers, status);
            }
            if (settings.archive) new HtmlArchive(settings.folder, target).index();
            int pending = settings.download ? store.pending(target, settings.folder).size() : 0;
            status.accept((isStopped() ? "Stopped" : "Scan finished") + " · " + pages + " pages · " + session.visible
                + " results · " + downloaded + " files saved" + (pending > 0 ? " · " + pending + " downloads pending" : ""));
        } catch (Exception e) {
            status.accept("Scan failed: " + e.getMessage());
        }
    }

    void queueDownloads(List<MediaItem> items, Path folder, TaskOptions options, Consumer<String> status) {
        try (ScanStore store = openStore()) {
            for (MediaItem item : items) {
                if (isStopped()) break;
                if (!item.downloadable()) continue;
                store.record(item.postUrl(), item);
                if (!store.downloaded(item.postUrl(), item))
                    store.enqueue(new ScanStore.Transfer(item.postUrl(), item, folder, options));
            }
            status.accept("Selected files added to the download queue");
        } catch (SQLException e) {
            status.accept("Unable to queue downloads: " + e.getMessage());
        }
    }

    void resumeDownloads(int workers, Consumer<String> status) {
        try (ScanStore store = openStore()) {
            var pending = store.pending(null, null, pasted);
            int saved = runTransfers(store, pending, null, null, workers, status);
            for (var archive : pending.stream().map(job -> new HtmlArchive.Location(job.folder(), job.source())).distinct().toList()) {
                new HtmlArchive(archive.folder(), archive.source()).index();
            }
            var remaining = store.pending(null, null, pasted);
            long needsCookies = remaining.stream().filter(job -> job.options().needsPastedCookies()).count();
            status.accept("Downloads · " + saved + " saved · " + remaining.size() + " pending"
                + (needsCookies == 0 ? "" : " · " + needsCookies + " need fresh cookies. Paste again and scan their source."));
        } catch (SQLException | InterruptedException | IOException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            status.accept("Download queue error: " + e.getMessage());
        }
    }

    private int runTransfers(ScanStore store, List<ScanStore.Transfer> jobs, TaskOptions options, List<Cookie> cookies,
                             int workers, Consumer<String> status) throws SQLException, InterruptedException, IOException {
        if (jobs.isEmpty() || isStopped()) return 0;
        try (DownloadQueue queue = new DownloadQueue(store, workers, status, network)) {
            transfers = queue;
            if (isStopped() || downloadsPaused.get()) queue.pause();
            int completed = queue.download(jobs, options, cookies);
            if (queue.isPaused()) status.accept("Downloads paused. Use Resume downloads to continue.");
            return completed;
        } finally {
            transfers = null;
        }
    }

    private int scanFeed(BrowserContext context, Session session) throws IOException, SQLException {
        var response = context.request().get(session.target, RequestOptions.create()
            .setTimeout(session.options.network().timeoutSeconds() * 1000.0));
        try {
            if (!response.ok()) throw new IOException("Feed returned HTTP " + response.status());
            String xml = response.text();
            if (xml.length() > 10_000_000) throw new IOException("Feed is larger than 10 MB");
            for (MediaItem item : MediaLists.feed(xml, response.url())) {
                if (isStopped()) break;
                session.accept(item);
            }
            return 1;
        } finally {
            response.dispose();
        }
    }

    private int crawl(URI target, BrowserContext context, Session session, Consumer<String> status) throws Exception {
        var saved = session.settings.updateOnly ? new ScanStore.Checkpoint(List.of(), List.of()) : session.store.checkpoint(session.target);
        Set<String> visited = new LinkedHashSet<>(saved.visited());
        Set<String> frontier = new LinkedHashSet<>(saved.pending());
        if (frontier.isEmpty()) frontier.add(target.toString());
        HtmlArchive archive = session.settings.archive ? new HtmlArchive(session.settings.folder, session.target) : null;
        Page page = context.newPage();
        page.setDefaultTimeout(session.options.network().timeoutSeconds() * 1000.0);
        int pages = 0;
        try {
            while (!frontier.isEmpty() && !isStopped() && pages < Math.clamp(session.settings.maxPages, 1, 500)) {
                String address = frontier.iterator().next();
                status.accept("Scanning page " + (++pages) + " · " + URI.create(address).getHost());
                if (PublicFeeds.isPage(address, target)) {
                    PublicFeeds.Result feed = PublicFeeds.fetch(context, address, target);
                    if (feed.restricted()) {
                        status.accept("The platform restricted the feed request. Scan stopped.");
                        stopped.set(true);
                        break;
                    }
                    if (!feed.handled()) {
                        status.accept("Feed page unavailable. Its cursor remains saved.");
                        break;
                    }
                    for (MediaItem item : feed.items()) session.accept(item);
                    if (feed.next() != null && !visited.contains(feed.next())) frontier.add(feed.next());
                    frontier.remove(address);
                    visited.add(address);
                    saveProgress(session, frontier, visited);
                    Thread.sleep(session.options.network().crawlDelayMs());
                    continue;
                }
                Response response;
                try {
                    response = navigate(page, address, session.options.network());
                } catch (PlaywrightException e) {
                    status.accept("Skipping an inaccessible page");
                    frontier.remove(address);
                    visited.add(address);
                    continue;
                }
                if (restricted(response) || accessWall(page)) {
                    status.accept("Access restricted or rate limited. Scan stopped.");
                    stopped.set(true);
                    break;
                }
                if (response != null && response.status() >= 400) {
                    status.accept("Skipping page with HTTP " + response.status());
                    frontier.remove(address);
                    visited.add(address);
                    continue;
                }
                if (address.equals(target.toString()) && !session.settings.archive) {
                    PublicFeeds.Result feed = PublicFeeds.begin(context, page, target);
                    if (feed.restricted()) {
                        status.accept("The platform restricted the feed request. Scan stopped.");
                        stopped.set(true);
                        break;
                    }
                    if (feed.handled()) {
                        for (MediaItem item : feed.items()) session.accept(item);
                        if (feed.next() != null) frontier.add(feed.next());
                        frontier.remove(address);
                        visited.add(address);
                        saveProgress(session, frontier, visited);
                        continue;
                    }
                }
                Set<String> candidates = new LinkedHashSet<>();
                Set<MediaItem> attachments;
                if (session.settings.mode == Mode.DIRECTORY) {
                    var directory = MediaLists.directory(page.content(), page.url());
                    attachments = new LinkedHashSet<>();
                    for (MediaItem file : directory.files()) if (session.accept(file)) attachments.add(file);
                    candidates.addAll(directory.folders());
                } else if (response != null && response.headers().getOrDefault("content-type", "")
                    .matches("(?i).*(?:application/(?:rss|atom)\\+xml).*")) {
                    attachments = new LinkedHashSet<>();
                    String xml = response.text();
                    if (xml.length() <= 10_000_000) {
                        for (MediaItem item : MediaLists.feed(xml, page.url())) if (session.accept(item)) attachments.add(item);
                    }
                } else {
                    attachments = extractPage(page, session, candidates);
                }
                if (archive != null) archive.save(page, address, attachments);
                if (address.equals(target.toString()) && !sameHost(target, webUri(page.url()))) {
                    visited.add(page.url());
                }
                for (String link : candidates) {
                    if (frontier.size() == MAX_FRONTIER) break;
                    URI candidate = webUri(link);
                    URI landed = webUri(page.url());
                    if (!sameHost(target, candidate) && !sameHost(landed, candidate)) continue;
                    if (!visited.contains(link) && !link.equals(address)) frontier.add(link);
                }
                if (!isStopped()) {
                    frontier.remove(address);
                    visited.add(address);
                }
                saveProgress(session, frontier, visited);
            }
        } finally {
            saveProgress(session, frontier, visited);
            if (archive != null) archive.index();
        }
        return pages;
    }

    private static void saveProgress(Session session, Set<String> frontier, Set<String> visited) throws SQLException {
        while (visited.size() > MAX_CHECKPOINT_VISITED) visited.remove(visited.iterator().next());
        if (frontier.isEmpty()) session.store.checkpoint(session.target, List.of(), List.of());
        else session.store.checkpoint(session.target, frontier, visited.stream().filter(url -> !frontier.contains(url)).toList());
    }

    private Set<MediaItem> extractPage(Page page, Session session, Set<String> candidates) throws SQLException {
        var attachments = new LinkedHashSet<MediaItem>();
        int idle = 0;
        for (int scroll = 0; scroll < 80 && !isStopped() && idle < 3; scroll++) {
            int previous = session.seen.size() + candidates.size();
            var result = PageExtractor.extract(page, session.rules);
            for (MediaItem item : result.items()) {
                if (session.accept(item) && session.settings.archive && item.downloadable()) attachments.add(item);
            }
            for (String link : result.links()) {
                if (candidates.size() < MAX_FRONTIER && candidates.add(link)) {
                    session.accept(new MediaItem(page.url(), link, "page"));
                }
            }
            idle = previous == session.seen.size() + candidates.size() ? idle + 1 : 0;
            if (advanceCarousel(page) || advanceResults(page)) {
                // Collection on the next iteration captures the new slide before another click.
                page.waitForTimeout(350);
                continue;
            }
            if (idle >= 3) break;
            try {
                page.evaluate("() => window.scrollBy(0, Math.max(500, window.innerHeight * .85))");
                page.waitForTimeout(session.options.network().crawlDelayMs());
            } catch (PlaywrightException ignored) {
                break;
            }
        }
        return attachments;
    }

    private static boolean advanceResults(Page page) {
        var buttons = page.locator("""
            button[data-testid=load-more],button[aria-label="Load more results"],
            button[aria-label="Show more results"],button[aria-label="Load more posts"]
            """);
        try {
            for (Locator button : buttons.all()) {
                if (button.isVisible() && button.isEnabled()
                    && button.evaluate("el => el.getBoundingClientRect().top < innerHeight * 1.5").equals(Boolean.TRUE)) {
                    button.click(new Locator.ClickOptions().setTimeout(700));
                    return true;
                }
            }
        } catch (PlaywrightException ignored) {}
        return false;
    }

    private static boolean advanceCarousel(Page page) {
        var next = page.locator("""
            button[aria-label="Next slide"],button[aria-label="Next image"],button.swiper-button-next,
            [aria-roledescription=carousel] button[aria-label=Next],article button[aria-label=Next],
            [data-testid=carousel] button[aria-label=Next],shreddit-post button[aria-label=Next]
            """);
        try {
            for (Locator button : next.all()) {
                if (button.isVisible() && button.isEnabled()) {
                    button.click(new Locator.ClickOptions().setTimeout(700));
                    return true;
                }
            }
        } catch (PlaywrightException ignored) {}
        return false;
    }

    record Refresh(MediaItem item, List<Cookie> cookies) {}

    static Refresh refresh(TaskOptions options, MediaItem old, Consumer<String> status) {
        status.accept("Refreshing a signed media link from its original post");
        try (Playwright playwright = playwright();
             Browser browser = browser(playwright, options);
             BrowserContext context = context(browser, options)) {
            Page page = context.newPage();
            Response response = navigate(page, old.postUrl(), options.network());
            if (restricted(response) || accessWall(page) || response != null && response.status() >= 400) return null;
            SiteRules rules = options.rules() == null ? SiteRules.empty() : SiteRules.load(options.rules());
            for (int i = 0; i < 8; i++) {
                MediaItem replacement = PageExtractor.matchingRefresh(page, rules, old);
                if (replacement != null) return new Refresh(replacement, context.cookies());
                if (!advanceCarousel(page)) break;
                page.waitForTimeout(350);
            }
        } catch (Exception e) {
            status.accept("Could not refresh signed link. The download remains pending.");
        }
        return null;
    }

    static URI webUri(String url) {
        URI uri = URI.create(url);
        if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "")) || uri.getHost() == null
            || uri.getUserInfo() != null) throw new IllegalArgumentException("Enter a valid http(s) URL without credentials");
        return uri;
    }

    private static boolean sameHost(URI first, URI second) {
        return first.getHost().replaceFirst("(?i)^www\\.", "").equalsIgnoreCase(second.getHost().replaceFirst("(?i)^www\\.", ""))
            && first.getPort() == second.getPort();
    }

    private static boolean restricted(Response response) {
        return response != null && RESTRICTED.contains(response.status());
    }

    private static boolean accessWall(Page page) {
        return URI.create(page.url()).getPath().matches("(?i).*/(?:login|signin|sign-in|logged_out|challenge|checkpoint|captcha)(?:/.*)?")
            || page.locator("form#challenge-form, iframe[src*='challenges.cloudflare.com'], iframe[src*='/recaptcha/api2/bframe']").count() > 0;
    }

    private static Response navigate(Page page, String url, NetworkSettings network) {
        return page.navigate(url, new Page.NavigateOptions().setTimeout(network.timeoutSeconds() * 1000.0).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
    }

    static Playwright playwright() {
        String executable = System.getProperty("media.chromium.path");
        if (executable == null) executable = System.getenv("MEDIA_CHROMIUM_PATH");
        if (executable == null) executable = bundledBrowser().map(Path::toString).orElse(null);
        Playwright.CreateOptions options = new Playwright.CreateOptions();
        if (executable != null || System.getProperty("media.chromium.channel") != null) options.setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));
        return Playwright.create(options);
    }

    static Browser browser(Playwright playwright, TaskOptions task) {
        return browser(playwright, task, true);
    }

    static Browser browser(Playwright playwright, TaskOptions task, boolean headless) {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(headless);
        if (!task.proxy().isBlank()) options.setProxy(new Proxy(task.proxy()).setBypass("<-loopback>"));
        String executable = System.getProperty("media.chromium.path");
        if (executable == null) executable = System.getenv("MEDIA_CHROMIUM_PATH");
        if (executable == null) executable = bundledBrowser().map(Path::toString).orElse(null);
        if (executable != null) options.setExecutablePath(Path.of(executable));
        else if (System.getProperty("media.chromium.channel") != null) options.setChannel(System.getProperty("media.chromium.channel"));
        return playwright.chromium().launch(options);
    }

    private static Optional<Path> bundledBrowser() {
        Path root = Path.of(System.getProperty("java.home")).getParent();
        if (root == null) return Optional.empty();
        Path browsers = root.resolve("app").resolve("browsers");
        if (!Files.isDirectory(browsers)) return Optional.empty();
        try (var files = Files.walk(browsers, 6)) {
            return files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equalsIgnoreCase("chrome.exe")).findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static BrowserContext context(Browser browser, TaskOptions options) throws IOException {
        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions();
        if (!options.network().userAgent().isBlank()) contextOptions.setUserAgent(options.network().userAgent());
        if (!options.network().headers().isEmpty()) contextOptions.setExtraHTTPHeaders(options.network().headers());
        BrowserContext context = browser.newContext(contextOptions);
        if (options.cookies() != null) context.addCookies(options.loadCookies());
        return context;
    }

    static String directType(URI uri) {
        String path = uri.getPath();
        if (path.toLowerCase(Locale.ROOT).endsWith(".m3u8")) return "hls";
        if (IMAGE.matcher(path).find()) return "image";
        if (VIDEO.matcher(path).find()) return "video";
        if (FILE.matcher(path).find()) return "file";
        return null;
    }

    private static final class Session {
        private final String target;
        private final TaskOptions options;
        private final Settings settings;
        private final SiteRules rules;
        private final ScanStore store;
        private final Consumer<MediaItem> onItem;
        private final Map<String, MediaItem> seen = new HashMap<>();
        private int visible;

        private Session(String target, TaskOptions options, Settings settings, SiteRules rules,
                        ScanStore store, Consumer<MediaItem> onItem) {
            this.target = target;
            this.options = options;
            this.settings = settings;
            this.rules = rules;
            this.store = store;
            this.onItem = onItem;
        }

        boolean accept(MediaItem item) throws SQLException {
            String url = item.url();
            if (!settings.mediaTypes.isEmpty() && !settings.mediaTypes.contains(item.type())) return false;
            if (!rules.allows(url) || !url.toLowerCase(Locale.ROOT).contains(settings.keyword)) return false;
            if (item.downloadable() && !settings.extensions.isEmpty()) {
                String path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
                String format = Arrays.stream(Objects.toString(URI.create(url).getRawQuery(), "").split("&"))
                    .filter(value -> value.startsWith("format=")).map(value -> value.substring(7)).findFirst().orElse("");
                if (!settings.extensions.contains(format)
                    && settings.extensions.stream().noneMatch(ext -> path.endsWith("." + ext))) return false;
            }
            String key = item.type() + "|" + item.identity();
            MediaItem old = seen.put(key, item);
            if (item.equals(old)) return true;
            boolean fresh = store.record(target, item);
            if (old == null && (!settings.updateOnly || fresh)) {
                onItem.accept(item);
                visible++;
            }
            if (settings.download && item.downloadable() && (!settings.updateOnly || fresh) && !store.downloaded(target, item)) {
                store.enqueue(new ScanStore.Transfer(target, item, settings.folder, options));
            }
            return true;
        }
    }
}
