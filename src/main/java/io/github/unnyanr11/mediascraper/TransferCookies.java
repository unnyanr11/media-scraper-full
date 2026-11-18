package io.github.unnyanr11.mediascraper;

import com.microsoft.playwright.options.Cookie;
import java.net.CookieHandler;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

final class TransferCookies extends CookieHandler {
    private final List<Cookie> cookies;

    TransferCookies(List<Cookie> cookies) {
        this.cookies = List.copyOf(cookies);
    }

    @Override public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        long now = Instant.now().getEpochSecond();
        String header = cookies.stream().filter(cookie -> matches(cookie, uri, host, path, now))
            .sorted(Comparator.comparingInt((Cookie cookie) -> cookie.path.length()).reversed())
            .map(cookie -> cookie.name + "=" + cookie.value).collect(Collectors.joining("; "));
        return header.isEmpty() ? Map.of() : Map.of("Cookie", List.of(header));
    }

    @Override public void put(URI uri, Map<String, List<String>> responseHeaders) {}

    private static boolean matches(Cookie cookie, URI uri, String host, String path, long now) {
        String domain = cookie.domain.toLowerCase(Locale.ROOT);
        boolean sameDomain = domain.startsWith(".")
            ? host.equals(domain.substring(1)) || host.endsWith(domain) : host.equals(domain);
        return sameDomain && (path.equals(cookie.path) || path.startsWith(cookie.path.endsWith("/") ? cookie.path : cookie.path + "/"))
            && (!Boolean.TRUE.equals(cookie.secure) || "https".equals(uri.getScheme()))
            && (cookie.expires == null || cookie.expires <= 0 || cookie.expires > now)
            && cookie.name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
            && cookie.value.chars().allMatch(ch -> ch >= 0x21 && ch <= 0x7E && ch != ';');
    }
}
