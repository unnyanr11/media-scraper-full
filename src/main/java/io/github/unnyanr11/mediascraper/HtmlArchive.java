package io.github.unnyanr11.mediascraper;

import com.microsoft.playwright.Page;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Save a readable HTML snapshot with an adjacent page metadata JSON file. */
public final class HtmlArchive {
    private HtmlArchive() {}

    public static void save(Page page, Path root, String source, int pageIndex) throws IOException {
        Files.createDirectories(root);
        String safe = Integer.toHexString(source.hashCode()) + "-" + pageIndex;
        String content = page.content();
        // Strip scripts, active embeds and forms from the offline copy.
        content = Pattern.compile("(?is)<script\\b[^>]*>.*?</script>").matcher(content).replaceAll("");
        content = Pattern.compile("(?is)<iframe\\b[^>]*>.*?</iframe>").matcher(content).replaceAll("");
        content = Pattern.compile("(?is)<form\\b[^>]*>.*?</form>").matcher(content).replaceAll("");
        Files.writeString(root.resolve(safe + ".html"), content);
        String title = page.title().replace("\\", "\\\\").replace("\"", "\\\"");
        String url = page.url().replace("\\", "\\\\").replace("\"", "\\\"");
        Files.writeString(root.resolve(safe + ".json"),
            "{\"title\":\"" + title + "\",\"url\":\"" + url + "\",\"page\":" + pageIndex + "}\n");
    }
}
