package io.github.unnyanr11.mediascraper;

import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Window;

final class BrowserLogin {
    private BrowserLogin() {}

    static void open(Window owner, String url, TaskOptions options,
                     java.util.function.Consumer<CookieInput> success, Label status) {
        var capture = new CompletableFuture<Boolean>();
        var dialog = new Dialog<Boolean>();
        dialog.initOwner(owner);
        dialog.setTitle("Sign in with browser");
        dialog.setContentText("Sign in in the browser window, then return here and choose Use session.");
        ButtonType use = new ButtonType("Use session", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(use, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == use);
        dialog.getDialogPane().lookupButton(use).setDisable(true);
        dialog.setOnHidden(event -> capture.complete(Boolean.TRUE.equals(dialog.getResult())));
        var thread = new Thread(() -> {
            try (var playwright = ScanService.playwright();
                 var browser = ScanService.browser(playwright, options, false);
                 var context = ScanService.context(browser, options);
                 var page = context.newPage()) {
                page.navigate(url, new com.microsoft.playwright.Page.NavigateOptions()
                    .setTimeout(options.network().timeoutSeconds() * 1000.0)
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
                Platform.runLater(() -> dialog.getDialogPane().lookupButton(use).setDisable(false));
                while (!capture.isDone()) {
                    if (page.isClosed() || !browser.isConnected()) throw new IllegalStateException("Browser was closed");
                    page.waitForTimeout(200);
                }
                if (capture.join()) {
                    var cookies = context.cookies();
                    if (cookies.isEmpty()) throw new IllegalStateException("The browser session has no cookies");
                    Platform.runLater(() -> success.accept(CookieInput.fromBrowser(cookies)));
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    dialog.close();
                    status.setText("Could not save browser session. Check that the browser opened and signed in.");
                });
            }
        }, "browser-login");
        thread.setDaemon(true);
        dialog.show();
        thread.start();
    }
}
