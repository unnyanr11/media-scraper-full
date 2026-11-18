package io.github.unnyanr11.mediascraper;

import com.microsoft.playwright.options.Cookie;
import java.io.IOException;
import java.util.List;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.file.Path;

record TaskOptions(CookieInput cookies, String proxy, Path rules, NetworkSettings network) {
    TaskOptions(CookieInput cookies, String proxy, Path rules) {
        this(cookies, proxy, rules, NetworkSettings.DEFAULT);
    }
    TaskOptions {
        if (!proxy.isBlank()) {
            URI address = URI.create(proxy);
            if (!"http".equals(address.getScheme()) || address.getHost() == null
                || address.getPort() < 1 || address.getPort() > 65535 || address.getUserInfo() != null
                || !address.getPath().isEmpty() || address.getQuery() != null || address.getFragment() != null) {
                throw new IllegalArgumentException("Use a proxy address such as http://proxy.example:8080");
            }
        }
    }

    boolean needsPastedCookies() {
        return cookies != null && !cookies.available();
    }

    List<Cookie> loadCookies() throws IOException {
        return cookies == null ? List.of() : cookies.load();
    }

    ProxySelector proxySelector() {
        if (proxy.isBlank()) return ProxySelector.of(null);
        URI address = URI.create(proxy);
        return ProxySelector.of(InetSocketAddress.createUnresolved(address.getHost(), address.getPort()));
    }
}
