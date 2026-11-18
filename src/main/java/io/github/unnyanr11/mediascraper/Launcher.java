package io.github.unnyanr11.mediascraper;

import java.util.Arrays;

/** Plain entry point for the Windows jpackage classpath launcher. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        if (Arrays.equals(args, new String[] {"--smoke-test"})) {
            smokeTest();
        } else {
            MainApp.main(args);
        }
    }

    private static void smokeTest() {
        // Resolve JavaFX classes and exercise the embedded browser without depending on a network connection.
        if (!javafx.application.Application.class.isAssignableFrom(MainApp.class)) {
            throw new IllegalStateException("JavaFX is unavailable");
        }
        try (var playwright = ScanService.playwright();
             var browser = ScanService.browser(playwright, new TaskOptions(null, "", null));
             var page = browser.newPage()) {
            page.setContent("<title>Media Scraper</title>");
            if (!"Media Scraper".equals(page.title())) throw new IllegalStateException("Chromium smoke test failed");
        }
    }
}
