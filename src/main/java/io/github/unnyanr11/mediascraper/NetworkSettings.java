package io.github.unnyanr11.mediascraper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

record NetworkSettings(int crawlDelayMs, int downloadDelayMs, int timeoutSeconds, int retries,
                       String userAgent, Map<String, String> headers) {
    static final NetworkSettings DEFAULT = new NetworkSettings(550, 300, 45, 1,
        "", Map.of());
    private static final Set<String> UNSAFE_HEADERS = Set.of("authorization", "proxy-authorization", "cookie",
        "host", "connection", "content-length", "expect", "upgrade", "range", "if-range",
        "accept-encoding", "user-agent", "origin", "referer", "transfer-encoding");

    NetworkSettings {
        if (crawlDelayMs < 0 || downloadDelayMs < 0 || timeoutSeconds < 1 || timeoutSeconds > 600
            || retries < 0 || retries > 5 || !printable(userAgent)) {
            throw new IllegalArgumentException("Check delay, timeout, retry, and user agent values.");
        }
        headers = Map.copyOf(headers);
        for (var header : headers.entrySet()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9!#$%&'*+.^_`|~-]+") || UNSAFE_HEADERS.contains(name)
                || name.startsWith("sec-") || name.startsWith("proxy-")
                || name.matches(".*(?:token|api-key|session|secret|password|auth).*") || !printable(header.getValue())) {
                throw new IllegalArgumentException("Invalid or reserved custom request header.");
            }
        }
    }

    private static boolean printable(String value) {
        return value.chars().allMatch(ch -> ch >= 0x20 && ch <= 0x7e);
    }

    static Map<String, String> parseHeaders(String lines) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : lines.lines().filter(row -> !row.isBlank()).toList()) {
            int delimiter = line.indexOf(':');
            if (delimiter < 1) throw new IllegalArgumentException("Enter custom headers as Name: value.");
            headers.put(line.substring(0, delimiter).strip(), line.substring(delimiter + 1).strip());
        }
        return headers;
    }
}
