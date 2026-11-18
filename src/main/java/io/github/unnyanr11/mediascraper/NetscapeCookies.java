package io.github.unnyanr11.mediascraper;

import com.microsoft.playwright.options.Cookie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class NetscapeCookies {
    private NetscapeCookies() {}

    public static List<Cookie> load(Path path) throws IOException {
        return parse(Files.readString(path));
    }

    static List<Cookie> parse(String text) throws IOException {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        var cookies = new ArrayList<Cookie>();
        int lineNumber = 0;
        long now = Instant.now().getEpochSecond();
        var lines = text.lines().iterator();
        while (lines.hasNext()) {
            lineNumber++;
            String line = lines.next();
            if (line.isBlank() || (line.startsWith("#") && !line.startsWith("#HttpOnly_"))) continue;
            boolean httpOnly = line.startsWith("#HttpOnly_");
            if (httpOnly) line = line.substring("#HttpOnly_".length());
            String[] fields = line.split("\t", -1);
            if (fields.length != 7) throw new IOException("Invalid Netscape cookie at line " + lineNumber);
            String domain = fields[0];
            if (domain.isBlank() || domain.contains("/") || domain.contains(" ")) {
                throw new IOException("Invalid cookie domain at line " + lineNumber);
            }
            long expires;
            try { expires = Long.parseLong(fields[4]); }
            catch (NumberFormatException e) { throw new IOException("Invalid cookie expiry at line " + lineNumber, e); }
            if (expires > 0 && expires <= now) continue;
            if (fields[5].isEmpty()) throw new IOException("Missing cookie name at line " + lineNumber);
            Cookie cookie = new Cookie(fields[5], fields[6]);
            String bareDomain = domain.startsWith(".") ? domain.substring(1) : domain;
            cookie.domain = Boolean.parseBoolean(fields[1]) ? "." + bareDomain : bareDomain;
            cookie.path = fields[2].isEmpty() ? "/" : fields[2];
            cookie.secure = Boolean.parseBoolean(fields[3]);
            cookie.httpOnly = httpOnly;
            if (expires > 0) cookie.expires = (double) expires;
            cookies.add(cookie);
        }
        return cookies;
    }
}
