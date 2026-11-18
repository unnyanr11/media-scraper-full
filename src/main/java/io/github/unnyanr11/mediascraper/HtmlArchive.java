package io.github.unnyanr11.mediascraper;

import com.google.gson.Gson;
import com.microsoft.playwright.Page;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.Collection;
import java.util.Map;

/** Stores readable, script-free copies and links them as their crawl pages become available. */
final class HtmlArchive {
    private static final Safelist CONTENT = Safelist.relaxed()
        .addTags("article", "section", "main", "details", "summary", "time", "mark", "hr")
        .addAttributes("time", "datetime").addAttributes(":all", "id");
    record Location(Path folder, String source) {}
    private final Path root;
    private final Path folder;

    HtmlArchive(Path folder, String source) {
        this.folder = folder;
        root = folder.resolve("archive").resolve(key(source));
    }

    void save(Page page, String requestedUrl, Collection<MediaItem> attachments) throws IOException {
        Files.createDirectories(root);
        String actual = page.url();
        Document original = Jsoup.parse(page.content(), actual);
        original.select("script,iframe,form,style,nav,[hidden]").remove();
        Document document = new Cleaner(CONTENT).clean(original);
        document.title(page.title());
        document.head().appendElement("meta").attr("charset", "UTF-8");
        document.head().appendElement("meta").attr("http-equiv", "Content-Security-Policy")
            .attr("content", "default-src 'none'; img-src 'self' file:; base-uri 'none'; form-action 'none'");
        var heading = document.body().prependElement("p");
        heading.appendElement("a").attr("href", "index.html").text("Archive index");
        heading.appendText(" · ");
        heading.appendElement("a").attr("href", actual).text("Original page");
        for (var image : document.select("img")) {
            String url = image.absUrl("src");
            if (url.startsWith("http://") || url.startsWith("https://")) {
                image.before(new org.jsoup.nodes.Element("a").attr("href", url)
                    .text(image.attr("alt").isBlank() ? "Original image" : image.attr("alt")));
            }
            image.remove();
        }
        if (!attachments.isEmpty()) {
            document.body().appendElement("h2").text("Attachments");
            var list = document.body().appendElement("ul");
            for (MediaItem item : attachments) {
                list.appendElement("li").appendElement("a")
                    .attr("href", item.url()).attr("data-file-key", DownloadQueue.fileKey(item))
                    .text(item.type() + " · " + URI.create(item.url()).getPath());
            }
        }
        String name = key(requestedUrl);
        Files.writeString(root.resolve(name + ".html"), document.outerHtml());
        Files.writeString(root.resolve(name + ".json"), new Gson().toJson(Map.of(
            "title", page.title(), "url", actual, "requestedUrl", requestedUrl)));
    }

    void index() throws IOException {
        if (!Files.isDirectory(root)) return;
        Document index = Document.createShell("");
        index.title("Page archive");
        index.head().appendElement("meta").attr("charset", "UTF-8");
        index.body().appendElement("h1").text("Page archive");
        var links = index.body().appendElement("ul");
        var localPages = new java.util.HashMap<String, String>();
        try (var files = Files.list(root)) {
            for (Path path : files.filter(file -> file.toString().endsWith(".json")).sorted().toList()) {
                var data = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                String href = path.getFileName().toString().replace(".json", ".html");
                links.appendElement("li").appendElement("a").attr("href", href)
                    .text(data.get("title").getAsString() + " · " + data.get("url").getAsString());
                localPages.put(data.get("requestedUrl").getAsString(), href);
                localPages.put(data.get("url").getAsString(), href);
            }
        }
        for (String name : new java.util.HashSet<>(localPages.values())) {
            Path path = root.resolve(name);
            Document page = Jsoup.parse(Files.readString(path));
            for (var link : page.select("a[data-file-key]")) {
                String key = link.attr("data-file-key");
                if (!key.matches("(?:image|video|file)_[a-f0-9]{32}")) continue;
                try (var assets = Files.newDirectoryStream(folder, key + ".*")) {
                    for (Path asset : assets) {
                        String nameOfAsset = asset.getFileName().toString();
                        if (nameOfAsset.endsWith(".part") || nameOfAsset.endsWith(".resume") || !Files.isRegularFile(asset)) continue;
                        link.attr("href", "../../" + nameOfAsset);
                        break;
                    }
                }
            }
            for (var link : page.select("a[href]")) {
                String href = link.attr("href");
                if (!href.startsWith("http://") && !href.startsWith("https://")) continue;
                if (link.text().equals("Original page")) continue;
                String[] parts = href.split("#", 2);
                String local = localPages.get(parts[0]);
                if (local != null) link.attr("href", local + (parts.length == 2 ? "#" + parts[1] : ""));
            }
            Files.writeString(path, page.outerHtml());
        }
        Files.writeString(root.resolve("index.html"), index.outerHtml());
    }

    private static String key(String url) {
        return DownloadQueue.fileKey(new MediaItem(url, URI.create(url).toString(), "page"));
    }
}
