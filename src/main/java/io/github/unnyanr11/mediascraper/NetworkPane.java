package io.github.unnyanr11.mediascraper;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

final class NetworkPane extends GridPane {
    private final Spinner<Integer> crawl = spinner(0, 60_000, NetworkSettings.DEFAULT.crawlDelayMs());
    private final Spinner<Integer> download = spinner(0, 60_000, NetworkSettings.DEFAULT.downloadDelayMs());
    private final Spinner<Integer> timeout = spinner(1, 600, NetworkSettings.DEFAULT.timeoutSeconds());
    private final Spinner<Integer> retries = spinner(0, 5, NetworkSettings.DEFAULT.retries());
    private final TextField agent = new TextField();
    private final TextArea headers = new TextArea();

    NetworkPane() {
        setPadding(new Insets(16));
        setHgap(14);
        setVgap(10);
        agent.setPromptText("Browser / downloader default");
        headers.setPromptText("One non-sensitive header per line, e.g. Accept-Language: en");
        headers.setPrefRowCount(4);
        addRow(0, new Label("Crawl delay (ms)"), crawl);
        addRow(1, new Label("Download delay per host (ms)"), download);
        addRow(2, new Label("Timeout (seconds)"), timeout);
        addRow(3, new Label("Download retries"), retries);
        addRow(4, new Label("User agent"), agent);
        addRow(5, new Label("Custom request headers"), headers);
    }

    NetworkSettings settings() {
        for (var spinner : java.util.List.of(crawl, download, timeout, retries)) spinner.commitValue();
        return new NetworkSettings(crawl.getValue(), download.getValue(), timeout.getValue(), retries.getValue(),
            agent.getText().strip(), NetworkSettings.parseHeaders(headers.getText()));
    }

    private static Spinner<Integer> spinner(int min, int max, int value) {
        var spinner = new Spinner<Integer>(min, max, value);
        spinner.setEditable(true);
        return spinner;
    }
}
