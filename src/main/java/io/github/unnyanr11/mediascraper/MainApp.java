package io.github.unnyanr11.mediascraper;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import javafx.scene.input.Clipboard;

public class MainApp extends Application {
    private final javafx.collections.ObservableList<MediaItem> discovered = FXCollections.observableArrayList();
    private final AtomicReference<ScanService> current = new AtomicReference<>();
    private Path cookiePath;
    private Path downloadPath;
    private final List<String> queuedUrls = new ArrayList<>();

    @Override public void start(Stage stage) {
        TextField url = new TextField();
        url.setPromptText("https://example.com/gallery");
        Label cookies = new Label("No cookies (public browsing)");
        Button pick = new Button("Browse cookies.txt");
        pick.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select Netscape cookies.txt");
            var file = chooser.showOpenDialog(stage);
            if (file != null) {
                cookiePath = file.toPath();
                cookies.setText(file.getName());
            }
        });
        Button clear = new Button("Clear");
        clear.setOnAction(e -> {cookiePath = null; cookies.setText("No cookies (public browsing)");});
        Label status = new Label("Ready");
        Label folderLabel = new Label("No download folder selected");
        Button chooseFolder = new Button("Download folder...");
        chooseFolder.setOnAction(e -> {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            var folder = chooser.showDialog(stage);
            if (folder != null) { downloadPath = folder.toPath(); folderLabel.setText(folder.getAbsolutePath()); }
        });
        CheckBox downloadAll = new CheckBox("Download all discovered images/videos");
        TableView<MediaItem> table = new TableView<>(discovered);
        TableColumn<MediaItem, String> type = new TableColumn<>("Type");
        type.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().type()));
        type.setPrefWidth(85);
        TableColumn<MediaItem, String> link = new TableColumn<>("Discovered URL");
        link.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().url()));
        link.setPrefWidth(720);
        table.getColumns().addAll(type, link);
        Button scan = new Button("Start scan");
        Button update = new Button("Update (new links)");
        Button history = new Button("Load saved links");
        Button stop = new Button("Stop");
        Label queueStatus = new Label("0 URLs queued");
        Button clipboard = new Button("Import clipboard");
        Button importFile = new Button("Import URL file");
        Button sequence = new Button("Generate sequence");
        Button clearQueue = new Button("Clear queue");
        clipboard.setOnAction(e -> {
            String raw = Clipboard.getSystemClipboard().hasString()
                ? Clipboard.getSystemClipboard().getString() : "";
            addQueued(UrlSources.fromText(raw), queueStatus);
        });
        importFile.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select URL list (.txt or .csv)");
            var file = chooser.showOpenDialog(stage);
            if (file == null) return;
            try { addQueued(UrlSources.fromFile(file.toPath()), queueStatus); }
            catch (Exception ex) { status.setText("Import error: " + ex.getMessage()); }
        });
        sequence.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog("https://example.org/image{001..100}.jpg");
            dialog.setTitle("Numbered URL sequence");
            dialog.setHeaderText("Generate sequential image or file URLs");
            dialog.setContentText("Template:");
            dialog.showAndWait().ifPresent(template -> {
                try { addQueued(UrlSources.expandSequence(template), queueStatus); }
                catch (Exception ex) { status.setText("Sequence error: " + ex.getMessage()); }
            });
        });
        clearQueue.setOnAction(e -> { queuedUrls.clear(); queueStatus.setText("0 URLs queued"); });
        Button export = new Button("Export CSV");
        stop.setDisable(true);
        scan.setOnAction(e -> runScan(false, url, downloadAll, scan, update, stop, status));
        update.setOnAction(e -> runScan(true, url, downloadAll, scan, update, stop, status));
        history.setOnAction(e -> {
            try (ScanStore store = new ScanStore()) {
                discovered.setAll(store.history(url.getText().strip()));
                status.setText("Loaded " + discovered.size() + " saved links");
            } catch (Exception ex) { status.setText("History error: " + ex.getMessage()); }
        });
        stop.setOnAction(e -> {
            ScanService service = current.get();
            if (service != null) service.stop();
            status.setText("Stopping after current operation...");
        });
        export.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setInitialFileName("media-links.csv");
            var file = chooser.showSaveDialog(stage);
            if (file == null) return;
            StringBuilder csv = new StringBuilder("type,post_url,url\n");
            for (MediaItem item : discovered) csv.append(quote(item.type())).append(',')
                .append(quote(item.postUrl())).append(',').append(quote(item.url())).append('\n');
            try { Files.writeString(file.toPath(), csv.toString()); status.setText("Exported " + discovered.size() + " links"); }
            catch (IOException ex) { status.setText("Export failed: " + ex.getMessage()); }
        });
        HBox cookieRow = new HBox(8, pick, clear, cookies);
        HBox controls = new HBox(8, scan, update, history, stop, export);
        VBox root = new VBox(12, new Label("Media Scraper Full"), url, cookieRow, new HBox(8, chooseFolder, folderLabel), downloadAll, new HBox(8, clipboard, importFile, sequence, clearQueue), queueStatus, controls, status, table);
        root.setPadding(new Insets(16));
        VBox.setVgrow(table, Priority.ALWAYS);
        stage.setTitle("Media Scraper Full");
        stage.setScene(new Scene(root, 960, 620));
        stage.show();
    }

    private void runScan(boolean updateOnly, TextField url, CheckBox downloadAll, Button scan, Button update,
                         Button stop, Label status) {
        String address = url.getText().strip();
        if (!address.matches("https?://.+")) { status.setText("Enter an http(s) URL"); return; }
        Path folder = downloadAll.isSelected() ? downloadPath : null;
        if (downloadAll.isSelected() && folder == null) { status.setText("Select a download folder first"); return; }
        discovered.clear();
        List<String> batch = queuedUrls.isEmpty() ? List.of(address) : List.copyOf(queuedUrls);
        ScanService service = new ScanService();
        current.set(service);
        scan.setDisable(true);
        update.setDisable(true);
        stop.setDisable(false);
        Path selectedCookies = cookiePath;
        Thread worker = new Thread(() -> {
            try {
                int number = 0;
                for (String source : batch) {
                    if (Thread.currentThread().isInterrupted()) break;
                    int currentNumber = ++number;
                    Platform.runLater(() -> status.setText("Scanning " + currentNumber + "/" + batch.size() + ": " + source));
                    service.scan(source, selectedCookies, folder, updateOnly,
                        item -> Platform.runLater(() -> discovered.add(item)),
                        message -> Platform.runLater(() -> status.setText("[" + currentNumber + "/" + batch.size() + "] " + message)));
                    if (service.isStopped()) break;
                }
            } finally {
                Platform.runLater(() -> {
                    scan.setDisable(false);
                    update.setDisable(false);
                    stop.setDisable(true);
                    current.compareAndSet(service, null);
                });
            }
        }, updateOnly ? "media-update" : "media-scan");
        worker.setDaemon(true);
        worker.start();
    }

    private void addQueued(List<String> additional, Label label) {
        LinkedHashSet<String> combined = new LinkedHashSet<>(queuedUrls);
        combined.addAll(additional);
        queuedUrls.clear();
        queuedUrls.addAll(combined);
        label.setText(queuedUrls.size() + " URLs queued");
    }

    private static String quote(String value) {return "\"" + value.replace("\"", "\"\"") + "\"";}
    public static void main(String[] args) {launch(args);}
}
