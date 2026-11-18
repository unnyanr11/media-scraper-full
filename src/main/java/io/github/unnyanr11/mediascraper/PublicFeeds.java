package io.github.unnyanr11.mediascraper;

import com.google.gson.*;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.RequestOptions;
import org.jsoup.Jsoup;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Cursor-based adapters where the platform provides anonymous, documented read APIs. */
final class PublicFeeds {
    private static final String BLUESKY = "https://public.api.bsky.app/xrpc/";
    private static final String AUTHOR_FEED = BLUESKY + "app.bsky.feed.getAuthorFeed?limit=50&actor=";
    private static final Result UNSUPPORTED = new Result(false, false, List.of(), null);
    private static final Result RESTRICTED = new Result(true, true, List.of(), null);

    record Result(boolean handled, boolean restricted, List<MediaItem> items, String next) {}
    private record Reply(JsonElement json, boolean restricted) {}

    static Result begin(BrowserContext context, Page page, URI source) {
        if (PageExtractor.siteFor(source.toString()).name().equals("bluesky")) {
            String[] parts = source.getPath().split("/");
            if (parts.length == 3 && parts[1].equals("profile")) return fetch(context, AUTHOR_FEED + encode(parts[2]), source);
            if (parts.length == 5 && parts[1].equals("profile") && parts[3].equals("post")) {
                return blueskyPost(context, parts[2], parts[4]);
            }
        }
        if (source.getPath().matches("/@[^/]+/?") && page.locator(
                "meta[name=application-name][content=Mastodon],.status__content,.status__wrapper").count() > 0) {
            String handle = source.getPath().substring(2).replaceFirst("/$", "");
            String origin = source.getScheme() + "://" + source.getRawAuthority();
            Reply lookup = get(context, origin + "/api/v1/accounts/lookup?acct=" + encode(handle));
            if (lookup.restricted) return RESTRICTED;
            String id = string(object(lookup.json), "id");
            if (id.matches("\\d+")) return fetch(context, origin + "/api/v1/accounts/" + id + "/statuses?limit=40&exclude_reblogs=true", source);
        }
        return UNSUPPORTED;
    }

    static boolean isPage(String address, URI source) {
        if (address.startsWith(AUTHOR_FEED) && PageExtractor.siteFor(source.toString()).name().equals("bluesky")) return true;
        URI uri = URI.create(address);
        return Objects.equals(uri.getScheme(), source.getScheme())
            && Objects.equals(uri.getRawAuthority(), source.getRawAuthority())
            && uri.getPath().matches("/api/v1/accounts/\\d+/statuses") && source.getPath().matches("/@[^/]+/?");
    }

    static Result fetch(BrowserContext context, String address, URI source) {
        if (!isPage(address, source)) return UNSUPPORTED;
        Reply reply = get(context, address);
        if (reply.restricted) return RESTRICTED;
        if (address.startsWith(AUTHOR_FEED)) return parseBluesky(reply.json, address);
        return parseMastodon(reply.json, address);
    }

    static Result parseBluesky(JsonElement response, String address) {
        JsonObject data = object(response);
        if (!data.has("feed")) return UNSUPPORTED;
        List<MediaItem> items = new ArrayList<>();
        for (JsonElement wrapper : array(data.get("feed"))) {
            JsonObject entry = object(wrapper);
            if (!entry.has("reason")) blueskyItems(object(entry.get("post")), items);
        }
        String cursor = string(data, "cursor");
        return new Result(true, false, items, cursor.isBlank() ? null
            : address.split("&cursor=", 2)[0] + "&cursor=" + encode(cursor));
    }

    private static Result blueskyPost(BrowserContext context, String actor, String id) {
        Reply reply = get(context, BLUESKY + "app.bsky.feed.getPostThread?depth=0&parentHeight=0&uri="
            + encode("at://" + actor + "/app.bsky.feed.post/" + id));
        if (reply.restricted) return RESTRICTED;
        List<MediaItem> items = new ArrayList<>();
        blueskyItems(object(object(object(reply.json).get("thread")).get("post")), items);
        return items.isEmpty() ? UNSUPPORTED : new Result(true, false, items, null);
    }

    private static void blueskyItems(JsonObject post, List<MediaItem> items) {
        String id = string(post, "uri");
        String[] parts = id.split("/");
        if (parts.length != 5 || !parts[2].matches("did:[A-Za-z0-9:._%-]+")
            || !parts[4].matches("[a-zA-Z0-9._~-]+")) return;
        String url = "https://bsky.app/profile/" + parts[2] + "/post/" + parts[4];
        JsonObject record = object(post.get("record"));
        String caption = string(record, "text");
        String time = string(record, "createdAt");
        items.add(new MediaItem(url, url, "post", id, time, caption, "post"));
        JsonObject embed = object(post.get("embed"));
        if (embed.has("media")) embed = object(embed.get("media"));
        for (JsonElement image : array(embed.get("images"))) {
            String full = string(object(image), "fullsize");
            if (downloadUrl(full)) {
                String asset = URI.create(full).getPath().replaceFirst(".*/", "");
                items.add(new MediaItem(url, full, "image", id, time, caption, asset));
            }
        }
        // AppView may provide only an HLS playlist for a clip. The post is still archived.
        String video = string(embed, "url");
        if (downloadUrl(video) && URI.create(video).getPath().endsWith(".mp4")) {
            items.add(new MediaItem(url, video, "video", id, time, caption, MediaItem.stableUrl(video)));
        }
    }

    static Result parseMastodon(JsonElement response, String address) {
        if (response == null || !response.isJsonArray()) return UNSUPPORTED;
        JsonArray entries = response.getAsJsonArray();
        List<MediaItem> items = new ArrayList<>();
        for (JsonElement element : entries) {
            JsonObject status = object(element);
            String id = string(status, "uri");
            String post = string(status, "url");
            if (!downloadUrl(post) || id.isBlank()) continue;
            String time = string(status, "created_at");
            String caption = Jsoup.parse(string(status, "content")).text();
            items.add(new MediaItem(post, post, "post", id, time, caption, "post"));
            for (JsonElement attachment : array(status.get("media_attachments"))) {
                JsonObject media = object(attachment);
                String url = string(media, "url");
                String asset = string(media, "id");
                String type = switch (string(media, "type")) {
                    case "image" -> "image";
                    case "video", "gifv" -> "video";
                    case "audio" -> "file";
                    default -> "";
                };
                if (!type.isEmpty() && !asset.isEmpty() && downloadUrl(url))
                    items.add(new MediaItem(post, url, type, id, time, caption, asset));
            }
        }
        String last = entries.isEmpty() ? "" : string(object(entries.get(entries.size() - 1)), "id");
        String next = last.matches("\\d+") ? address.split("&max_id=", 2)[0] + "&max_id=" + last : null;
        return new Result(true, false, items, address.equals(next) ? null : next);
    }

    private static Reply get(BrowserContext context, String address) {
        try {
            var response = context.request().get(address, RequestOptions.create().setTimeout(15_000).setMaxRedirects(0));
            try {
                if (Set.of(401, 403, 429).contains(response.status())) return new Reply(null, true);
                if (response.ok()) return new Reply(JsonParser.parseString(response.text()), false);
            } finally {
                response.dispose();
            }
        } catch (PlaywrightException | JsonParseException ignored) {}
        return new Reply(null, false);
    }

    private static boolean downloadUrl(String value) {
        try {
            ScanService.webUri(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static JsonObject object(JsonElement node) {
        return node != null && node.isJsonObject() ? node.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonElement node) {
        return node != null && node.isJsonArray() ? node.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
