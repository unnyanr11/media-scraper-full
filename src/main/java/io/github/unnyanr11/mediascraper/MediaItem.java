package io.github.unnyanr11.mediascraper;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

public record MediaItem(String postUrl, String url, String type, String postId,
                        String publishedAt, String caption, String assetId) {
    private static final Set<String> TRANSIENT_PARAMETERS = Set.of(
        "token", "sig", "signature", "expire", "expires", "expiry", "exp", "policy", "key-pair-id", "se",
        "st", "sp", "sv", "srt", "sr", "skoid", "sktid", "skt", "ske", "sks", "skv");

    public MediaItem(String postUrl, String url, String type) {
        this(postUrl, url, type, "", "", "", "");
    }

    public boolean downloadable() {
        return type.equals("image") || type.equals("video") || type.equals("file") || type.equals("hls");
    }

    String identity() {
        return assetId.isBlank() ? url : postId + "|" + assetId;
    }

    static boolean isSigned(String url) {
        URI uri = URI.create(url);
        return uri.getRawQuery() != null &&
            java.util.Arrays.stream(uri.getRawQuery().split("&")).anyMatch(MediaItem::transientParameter);
    }

    static String stableUrl(String url) {
        URI uri = URI.create(url);
        if (uri.getRawQuery() == null) return url.split("#", 2)[0];
        String query = java.util.Arrays.stream(uri.getRawQuery().split("&"))
            .filter(part -> !transientParameter(part)).collect(java.util.stream.Collectors.joining("&"));
        return url.split("[?#]", 2)[0] + (query.isEmpty() ? "" : "?" + query);
    }

    private static boolean transientParameter(String parameter) {
        String key = parameter.split("=", 2)[0].toLowerCase(Locale.ROOT);
        return TRANSIENT_PARAMETERS.contains(key) || key.startsWith("x-amz-") || key.startsWith("x-goog-");
    }
}
