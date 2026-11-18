package io.github.unnyanr11.mediascraper;

import com.microsoft.playwright.options.Cookie;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** File path or in-memory cookies. Only the opaque reference is stored in pending jobs. */
record CookieInput(String reference, List<Cookie> pasted) {
    private static final String PASTED = "paste:";

    static CookieInput file(Path path) {
        return new CookieInput(path.toAbsolutePath().normalize().toString(), null);
    }

    static CookieInput paste(String text) throws IOException {
        List<Cookie> cookies = NetscapeCookies.parse(text);
        if (cookies.isEmpty()) throw new IOException("Paste at least one unexpired Netscape cookie.");
        return new CookieInput(PASTED + UUID.randomUUID(), List.copyOf(cookies));
    }

    static CookieInput restore(String reference, Map<String, CookieInput> inMemory) {
        if (reference == null) return null;
        if (!reference.startsWith(PASTED)) return file(Path.of(reference));
        return inMemory.getOrDefault(reference, new CookieInput(reference, null));
    }

    boolean available() {
        return !reference.startsWith(PASTED) || pasted != null;
    }

    List<Cookie> load() throws IOException {
        if (!available()) throw new IOException("Paste the cookies again for this task.");
        if (pasted == null) return NetscapeCookies.load(Path.of(reference));
        long now = Instant.now().getEpochSecond();
        List<Cookie> valid = pasted.stream().filter(cookie -> cookie.expires == null || cookie.expires > now).toList();
        if (valid.isEmpty()) throw new IOException("The pasted cookies expired. Paste fresh cookies for this task.");
        return valid;
    }

    String label() {
        return reference.startsWith(PASTED) ? "Pasted cookies" : Path.of(reference).getFileName().toString();
    }

    @Override public String toString() { return label(); }
}
