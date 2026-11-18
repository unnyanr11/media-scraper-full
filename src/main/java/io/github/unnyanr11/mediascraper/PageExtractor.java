package io.github.unnyanr11.mediascraper;

import com.microsoft.playwright.Page;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Selects a site adapter by host and passes its selectors to the browser-side extractor. */
final class PageExtractor {
    record Site(String name, List<String> hosts, String post, String permalink, String caption, String media,
                boolean search) {
        boolean matches(String host) {
            return hosts.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
        }
    }

    private static final Site GENERIC = new Site("generic", List.of(), "", "", "", "", false);
    private static final List<Site> SITES = List.of(
        site("instagram", "instagram.com", "article", "/p/|/reel/|/tv/", "h1,[data-testid=post-comment-root]", "img,video,video source"),
        site("x", "x.com twitter.com", "article[data-testid=tweet]", "/status/", "[data-testid=tweetText]", "[data-testid=tweetPhoto] img,video,video source"),
        site("pinterest", "pinterest.com pinterest.co.uk pinterest.ca pinterest.de pinterest.fr pinterest.in pinterest.com.au", "[data-test-id=pin]", "/pin/", "[data-test-id=pin-description]", "img,video,video source"),
        site("reddit", "reddit.com", "shreddit-post,article", "/comments/", "[slot=text-body],h1,h3", "img,video,video source"),
        site("youtube", "youtube.com youtu.be", "ytd-rich-item-renderer,ytd-grid-video-renderer,ytd-video-renderer,ytd-playlist-video-renderer,ytd-watch-flexy", "/watch?v=|/shorts/", "#video-title,h1", "img,video,video source"),
        site("imgur", "imgur.com", ".Gallery-Content,.Post-item-container,article", "/gallery/|/a/", ".PostTitle,.Gallery-Title,h1", "img,video,video source"),
        site("deviantart", "deviantart.com", "[data-hook=deviation],article", "/art/|/journal/", "h1,h2,[data-hook=deviation-title]", "img,video,video source"),
        site("bluesky", "bsky.app", "[data-testid^=feedItem-by-], [data-testid^=postThreadItem-by-]", "/post/", "[data-testid=postText]", "img,video,video source"),
        new Site("google", List.of(), "", "", "", "", true),
        new Site("bing", List.of("bing.com"), "", "", "", "", true),
        new Site("yandex", List.of("yandex.com","yandex.ru","yandex.kz","yandex.by","ya.ru"), "", "", "", "", true));
    private static final Site MASTODON = site("mastodon", "", "article,.status", "/@", ".status__content,[data-testid=status-content]", "img,video,video source");
    private static final String SCRIPT;

    static {
        try (var stream = PageExtractor.class.getResourceAsStream("extract.js")) {
            SCRIPT = new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Site site(String name, String hosts, String post, String permalink, String caption, String media) {
        return new Site(name, hosts.isEmpty() ? List.of() : List.of(hosts.split(" ")), post, permalink, caption, media, false);
    }

    static Site siteFor(String url) {
        String host = URI.create(url).getHost().toLowerCase(Locale.ROOT);
        if (host.matches("(?:www\\.|images\\.)?google\\.(?:com|[a-z]{2,3}|(?:co|com)\\.[a-z]{2})"))
            return SITES.stream().filter(site -> site.name.equals("google")).findFirst().orElseThrow();
        return SITES.stream().filter(site -> site.matches(host)).findFirst().orElse(GENERIC);
    }

    record PageResult(List<MediaItem> items, List<String> links) {}

    static PageResult extract(Page page, SiteRules rules) {
        Site site = siteFor(page.url());
        if (site == GENERIC && page.locator("meta[name=application-name][content=Mastodon],.status__content,.status__wrapper").count() > 0)
            site = MASTODON;
        Object result = page.evaluate(SCRIPT, Map.of(
            "site", site.name, "post", site.post, "permalinks", site.permalink,
            "caption", site.caption, "media", site.media, "search", site.search, "rules", rules.commands()));
        if (!(result instanceof Map<?, ?> data)) return new PageResult(List.of(), List.of());
        List<MediaItem> items = new ArrayList<>();
        for (Object row : (List<?>) data.get("media")) {
            Map<?, ?> media = (Map<?, ?>) row;
            items.add(new MediaItem(field(media, "post"), field(media, "url"), field(media, "type"),
                field(media, "id"), field(media, "time"), field(media, "caption"), field(media, "asset")));
        }
        List<String> links = ((List<?>) data.get("links")).stream().map(Object::toString).toList();
        return new PageResult(items, links);
    }

    static MediaItem matchingRefresh(Page page, SiteRules rules, MediaItem old) {
        return extract(page, rules).items.stream()
            .filter(item -> item.type().equals(old.type()) && item.identity().equals(old.identity()))
            .filter(item -> !item.url().equals(old.url())).findFirst().orElse(null);
    }

    private static String field(Map<?, ?> row, String key) {
        return row.get(key) instanceof String value ? value : "";
    }
}
