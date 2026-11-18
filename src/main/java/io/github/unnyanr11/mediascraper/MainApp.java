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

public class MainApp extends Application {
    private final javafx.collections.ObservableList<MediaItem> discovered = FXCollections.observableArrayList();
    private final AtomicReference<ScanService> current = new AtomicReference<>();
    private Path cookiePath;
    private Path downloadPath;

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
        Button stop = new Button("Stop");
        Button export = new Button("Export CSV");
        stop.setDisable(true);
        scan.setOnAction(e -> {
            String address = url.getText().strip();
            if (!address.matches("https?://.+")) {status.setText("Enter an http(s) URL"); return;}
            discovered.clear();
            ScanService service = new ScanService();
            current.set(service);
            scan.setDisable(true);
            stop.setDisable(false);
            Path selectedCookies = cookiePath;
            Path selectedFolder = downloadAll.isSelected() ? downloadPath : null;
            if (downloadAll.isSelected() && selectedFolder == null) {
                status.setText("Select a download folder first");
                scan.setDisable(false); stop.setDisable(true); return;
            }
            Thread worker = new Thread(() -> {
                try {
                    service.scan(address, selectedCookies, selectedFolder,
                        item -> Platform.runLater(() -> discovered.add(item)),
                        message -> Platform.runLater(() -> status.setText(message)));
                } finally {
                    Platform.runLater(() -> {scan.setDisable(false); stop.setDisable(true); current.compareAndSet(service, null);});
                }
            }, "media-scan");
            worker.setDaemon(true);
            worker.start();
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
        HBox controls = new HBox(8, scan, stop, export);
        VBox root = new VBox(12, new Label("Media Scraper Full"), url, cookieRow, new HBox(8, chooseFolder, folderLabel), downloadAll, controls, status, table);
        root.setPadding(new Insets(16));
        VBox.setVgrow(table, Priority.ALWAYS);
        stage.setTitle("Media Scraper Full");
        stage.setScene(new Scene(root, 960, 620));
        stage.show();
    }

    private static String quote(String value) {return "\"" + value.replace("\"", "\"\"") + "\"";}
    public static void main(String[] args) {launch(args);}
}
