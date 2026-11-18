package io.github.unnyanr11.mediascraper;

import java.net.URI;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

final class MediaLists {
    private MediaLists() {}

    static List<MediaItem> feed(String xml, String source) {
        var root = Jsoup.parse(xml, source, Parser.xmlParser());
        var items = new LinkedHashMap<String, MediaItem>();
        for (var entry : root.select("item,entry")) {
            var link = entry.selectFirst("link:not([rel]),link[rel=alternate]");
            String post = link == null ? source : resolve(source, link.hasAttr("href") ? link.attr("href") : link.text());
            if (post == null) post = source;
            String id = entry.select("guid,id").text();
            String date = entry.select("pubDate,published,updated").text();
            String caption = entry.select("title").text();
            for (var attachment : entry.getAllElements()) {
                String name = attachment.tagName().toLowerCase(Locale.ROOT);
                if (!name.equals("enclosure") && !name.equals("media:content")
                    && !(name.equals("link") && attachment.attr("rel").equalsIgnoreCase("enclosure"))) continue;
                String url = resolve(source, attachment.hasAttr("href") ? attachment.attr("href") : attachment.attr("url"));
                if (url == null) continue;
                String mime = attachment.attr("type").toLowerCase(Locale.ROOT);
                String kind = mime.startsWith("image/") ? "image" : mime.startsWith("video/") ? "video" : "file";
                if (URI.create(url).getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")) kind = "hls";
                String asset = MediaItem.stableUrl(url);
                String postId = id.isBlank() ? post : source + "#" + id;
                items.putIfAbsent(postId + "|" + asset, new MediaItem(post, url, kind, postId, date, caption, asset));
            }
        }
        return List.copyOf(items.values());
    }

    record Directory(List<MediaItem> files, List<String> folders) {}

    static Directory directory(String html, String source) {
        URI base = ScanService.webUri(source);
        if (!base.getPath().endsWith("/")) return new Directory(List.of(), List.of());
        var files = new LinkedHashMap<String, MediaItem>();
        var folders = new LinkedHashSet<String>();
        for (var anchor : Jsoup.parse(html, source).select("a[href]")) {
            String address = resolve(source, anchor.attr("href"));
            if (address == null) continue;
            URI url = URI.create(address).normalize();
            if (!Objects.equals(base.getScheme(), url.getScheme()) || !Objects.equals(base.getAuthority(), url.getAuthority())
                || !url.getPath().startsWith(base.getPath()) || url.getPath().equals(base.getPath())
                || Arrays.asList(url.getPath().split("/")).contains("..")) continue;
            String target = url.toString().split("#", 2)[0];
            if (url.getPath().endsWith("/")) {
                folders.add(target);
            } else {
                String type = ScanService.directType(url);
                files.putIfAbsent(target, new MediaItem(source, target, type == null ? "file" : type));
            }
        }
        return new Directory(List.copyOf(files.values()), List.copyOf(folders));
    }

    static String resolve(String base, String value) {
        if (value.isBlank()) return null;
        try { return ScanService.webUri(URI.create(base).resolve(value).toString()).toString(); }
        catch (IllegalArgumentException e) { return null; }
    }
}
