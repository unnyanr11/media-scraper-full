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
import javafx.stage.Window;
import javafx.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.List;
import javafx.scene.input.Clipboard;

public class MainApp extends Application {
    private final javafx.collections.ObservableList<MediaItem> discovered = FXCollections.observableArrayList();
    private final AtomicReference<ScanService> current = new AtomicReference<>();
    private CookieInput cookieInput;
    private final Map<String, CookieInput> pastedCookies = new HashMap<>();
    private Path downloadPath;
    private Path rulesPath;
    private final List<String> queuedUrls = new ArrayList<>();
    private final Map<String, SessionSelection> sessions = new HashMap<>();

    private record SessionSelection(CookieInput cookies, String proxy) {}
    private record ScanTask(String source, TaskOptions options) {}

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
                cookieInput = CookieInput.file(file.toPath());
                cookies.setText(file.getName());
            }
        });
        Button paste = new Button("Paste cookies…");
        paste.setOnAction(e -> pasteCookies(stage).ifPresent(input -> {
            cookieInput = input;
            cookies.setText(input.label());
        }));
        Button clear = new Button("Clear");
        clear.setOnAction(e -> {cookieInput = null; cookies.setText("No cookies (public browsing)");});
        TextField proxy = new TextField();
        proxy.setPromptText("HTTP proxy, e.g. http://proxy.example:8080");
        Button configureQueue = new Button("Configure queued tasks");
        configureQueue.setOnAction(e -> configureQueue(stage, proxy.getText()));
        Label status = new Label("Ready");
        Label folderLabel = new Label("No download folder selected");
        Button chooseFolder = new Button("Download folder...");
        chooseFolder.setOnAction(e -> {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            var folder = chooser.showDialog(stage);
            if (folder != null) { downloadPath = folder.toPath(); folderLabel.setText(folder.getAbsolutePath()); }
        });
        CheckBox downloadAll = new CheckBox("Download discovered files");
        CheckBox archive = new CheckBox("Archive pages");
        CheckBox parallel = new CheckBox("Parallel downloads");
        parallel.setSelected(true);
        Spinner<Integer> workers = new Spinner<>(1, 20, 4);
        workers.setEditable(true);
        workers.setPrefWidth(75);
        Button rules = new Button("Load site rules...");
        Label rulesLabel = new Label("No rules");
        rules.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose site rules");
            var file = chooser.showOpenDialog(stage);
            if (file != null) { rulesPath = file.toPath(); rulesLabel.setText(file.getName()); }
        });
        Spinner<Integer> pageLimit = new Spinner<>(1, 500, 10);
        pageLimit.setEditable(true);
        pageLimit.setPrefWidth(85);
        TextField extensionFilter = new TextField();
        extensionFilter.setPromptText("File extensions: jpg,png,mp4,pdf,zip (optional)");
        TextField urlFilter = new TextField();
        urlFilter.setPromptText("Optional URL keyword filter");
        HBox crawlerSettings = new HBox(8, new Label("Max pages:"), pageLimit, urlFilter, extensionFilter);
        TableView<MediaItem> table = new TableView<>(discovered);
        TableColumn<MediaItem, String> type = new TableColumn<>("Type");
        type.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().type()));
        type.setPrefWidth(85);
        TableColumn<MediaItem, String> link = new TableColumn<>("Discovered URL");
        link.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().url()));
        link.setPrefWidth(720);
        table.getColumns().addAll(List.of(type, link));
        Button scan = new Button("Start scan");
        Button update = new Button("Update (new links)");
        Button history = new Button("Load saved links");
        Button stop = new Button("Stop");
        Label queueStatus = new Label("0 URLs queued");
        Button clipboard = new Button("Import clipboard");
        Button importFile = new Button("Import URL file");
        Button sequence = new Button("Generate sequence");
        Button clearQueue = new Button("Clear queue");
        Button savedSources = new Button("Queue saved sources");
        savedSources.setOnAction(e -> {
            try (ScanStore store = new ScanStore()) {
                addQueued(store.sources(), queueStatus);
                status.setText("Loaded saved source URLs into the queue");
            } catch (Exception ex) { status.setText("Unable to load saved sources: " + ex.getMessage()); }
        });
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
        clearQueue.setOnAction(e -> { queuedUrls.clear(); sessions.clear(); queueStatus.setText("0 URLs queued"); });
        Button export = new Button("Export CSV");
        Button exportJson = new Button("Export JSON");
        Button backup = new Button("Export source backup");
        Button pause = new Button("Pause downloads");
        Button resume = new Button("Resume downloads");
        stop.setDisable(true);
        pause.setDisable(true);
        java.util.function.Consumer<Boolean> startScan = incremental -> {
            try {
                pageLimit.commitValue();
                workers.commitValue();
                if ((downloadAll.isSelected() || archive.isSelected()) && downloadPath == null) {
                    status.setText("Select an output folder for downloads or archives");
                    return;
                }
                var settings = new ScanService.Settings(downloadPath, downloadAll.isSelected(), archive.isSelected(),
                    incremental, pageLimit.getValue(), urlFilter.getText().strip().toLowerCase(Locale.ROOT),
                    ScanService.Settings.extensions(extensionFilter.getText()), parallel.isSelected() ? workers.getValue() : 1);
                var batch = new ArrayList<ScanTask>();
                List<String> addresses = queuedUrls.isEmpty() ? List.of(url.getText().strip()) : List.copyOf(queuedUrls);
                for (String source : addresses) {
                    ScanService.webUri(source);
                    SessionSelection selection = sessions.getOrDefault(source, new SessionSelection(cookieInput, proxy.getText()));
                    batch.add(new ScanTask(source, new TaskOptions(selection.cookies(), selection.proxy().strip(), rulesPath)));
                }
                // Keep previous task credentials available for pending downloads until the app closes.
                for (ScanTask task : batch) {
                    CookieInput input = task.options().cookies();
                    if (input != null && input.pasted() != null) pastedCookies.put(input.reference(), input);
                }
                discovered.clear();
                startWorker(scan, update, resume, stop, pause, status, (service, report) -> {
                    for (int i = 0; i < batch.size() && !service.isStopped(); i++) {
                        ScanTask task = batch.get(i);
                        String prefix = "[" + (i + 1) + "/" + batch.size() + "] ";
                        report.accept(prefix + "Starting scan");
                        service.scan(task.source(), task.options(), settings,
                            item -> Platform.runLater(() -> discovered.add(item)), message -> report.accept(prefix + message));
                    }
                });
            } catch (IllegalArgumentException ex) {
                status.setText(ex.getMessage());
            }
        };
        scan.setOnAction(e -> startScan.accept(false));
        update.setOnAction(e -> startScan.accept(true));
        pause.setOnAction(e -> {
            ScanService service = current.get();
            if (service != null) service.pauseDownloads();
            status.setText("Downloads pause now or after the active scan. The queue is saved.");
        });
        resume.setOnAction(e -> {
            try {
                workers.commitValue();
                int count = parallel.isSelected() ? workers.getValue() : 1;
                startWorker(scan, update, resume, stop, pause, status,
                    (service, report) -> service.resumeDownloads(count, report));
            } catch (IllegalArgumentException ex) {
                status.setText(ex.getMessage());
            }
        });
        backup.setOnAction(e -> {
            String source = url.getText().strip();
            FileChooser chooser = new FileChooser();
            chooser.setInitialFileName("source-backup.json");
            var file = chooser.showSaveDialog(stage);
            if (file == null) return;
            try (ScanStore store = new ScanStore()) {
                var entries = store.historyEntries(source);
                JsonExporter.writeBackup(file.toPath(), source, entries);
                status.setText("Exported " + entries.size() + " source records");
            } catch (Exception ex) { status.setText("Backup export failed: " + ex.getMessage()); }
        });
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
        exportJson.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setInitialFileName("media-links.json");
            var file = chooser.showSaveDialog(stage);
            if (file == null) return;
            try {
                JsonExporter.write(file.toPath(), List.copyOf(discovered));
                status.setText("Exported " + discovered.size() + " entries as JSON");
            } catch (IOException ex) { status.setText("JSON export failed: " + ex.getMessage()); }
        });
        FlowPane cookieRow = new FlowPane(8, 6, paste, pick, clear, cookies, proxy);
        FlowPane controls = new FlowPane(8, 6, scan, update, history, stop, pause, resume, export, exportJson, backup);
        VBox root = new VBox(12, new Label("Media Scraper Full"), url, cookieRow, new HBox(8, chooseFolder, folderLabel), downloadAll, new FlowPane(8, 6, clipboard, importFile, sequence, savedSources, configureQueue, clearQueue), new FlowPane(8, 6, archive, parallel, new Label("Workers"), workers, rules, rulesLabel), crawlerSettings, queueStatus, controls, status, table);
        root.setPadding(new Insets(16));
        VBox.setVgrow(table, Priority.ALWAYS);
        stage.setTitle("Media Scraper Full");
        stage.setScene(new Scene(root, 960, 620));
        stage.setMinWidth(720);
        stage.setMinHeight(520);
        stage.show();
    }

    private void startWorker(Button scan, Button update, Button resume, Button stop, Button pause, Label status,
                             java.util.function.BiConsumer<ScanService, java.util.function.Consumer<String>> work) {
        ScanService service = new ScanService(null, pastedCookies);
        if (!current.compareAndSet(null, service)) return;
        List<Button> startButtons = List.of(scan, update, resume);
        List<Button> stopButtons = List.of(stop, pause);
        startButtons.forEach(button -> button.setDisable(true));
        stopButtons.forEach(button -> button.setDisable(false));
        Thread worker = new Thread(() -> {
            try {
                work.accept(service, message -> Platform.runLater(() -> status.setText(message)));
            } finally {
                Platform.runLater(() -> {
                    startButtons.forEach(button -> button.setDisable(false));
                    stopButtons.forEach(button -> button.setDisable(true));
                    current.compareAndSet(service, null);
                });
            }
        }, "media-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void configureQueue(Stage stage, String defaultProxy) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Queued scan sessions");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ListView<String> sources = new ListView<>(FXCollections.observableArrayList(queuedUrls));
        sources.setPrefSize(650, 240);
        Label selection = new Label("Select a queued URL");
        Button choose = new Button("Browse cookies.txt…");
        Button paste = new Button("Paste cookies…");
        Button publicSession = new Button("Use public session");
        Button defaults = new Button("Use scan defaults");
        sources.getSelectionModel().selectedItemProperty().addListener((observable, before, source) ->
            showSession(selection, sessions.get(source)));
        choose.setOnAction(e -> {
            String source = sources.getSelectionModel().getSelectedItem();
            if (source == null) return;
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select Netscape cookies for this URL");
            var file = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (file == null) return;
            editSession(source, CookieInput.file(file.toPath()), defaultProxy, selection);
        });
        paste.setOnAction(e -> {
            String source = sources.getSelectionModel().getSelectedItem();
            if (source == null) return;
            pasteCookies(dialog.getDialogPane().getScene().getWindow())
                .ifPresent(input -> editSession(source, input, defaultProxy, selection));
        });
        publicSession.setOnAction(e -> {
            String source = sources.getSelectionModel().getSelectedItem();
            if (source != null) editSession(source, null, defaultProxy, selection);
        });
        defaults.setOnAction(e -> {
            sessions.remove(sources.getSelectionModel().getSelectedItem());
            showSession(selection, null);
        });
        dialog.getDialogPane().setContent(new VBox(8, sources, selection, new HBox(8, paste, choose, publicSession, defaults)));
        dialog.showAndWait();
    }

    private static Optional<CookieInput> pasteCookies(Window owner) {
        Dialog<CookieInput> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Paste cookies");
        TextArea input = new TextArea();
        input.setPromptText("Paste the full tab-separated Netscape cookies.txt text here");
        input.setPrefColumnCount(64);
        input.setPrefRowCount(10);
        Label feedback = new Label("Pasted cookies stay in memory until you close the app.");
        feedback.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(8, input, feedback));
        ButtonType use = new ButtonType("Use cookies", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(use, ButtonType.CANCEL);
        var parsed = new AtomicReference<CookieInput>();
        dialog.getDialogPane().lookupButton(use).addEventFilter(ActionEvent.ACTION, event -> {
            try {
                parsed.set(CookieInput.paste(input.getText()));
            } catch (IOException ex) {
                feedback.setText(ex.getMessage());
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == use ? parsed.get() : null);
        dialog.setOnShown(event -> Platform.runLater(input::requestFocus));
        dialog.setOnHidden(event -> input.clear());
        return dialog.showAndWait();
    }

    private void editSession(String source, CookieInput input, String defaultProxy, Label label) {
        String previous = sessions.getOrDefault(source, new SessionSelection(input, defaultProxy)).proxy();
        TextInputDialog dialog = new TextInputDialog(previous);
        dialog.setTitle("Task proxy");
        dialog.setHeaderText("Enter an HTTP proxy or leave blank for a direct connection");
        dialog.showAndWait().ifPresent(value -> {
            try {
                String address = value.strip();
                new TaskOptions(input, address, null);
                SessionSelection session = new SessionSelection(input, address);
                sessions.put(source, session);
                showSession(label, session);
            } catch (IllegalArgumentException ex) {
                label.setText(ex.getMessage());
            }
        });
    }

    private static void showSession(Label label, SessionSelection selection) {
        if (selection == null) {
            label.setText("Uses the scan's default cookies and proxy");
        } else {
            label.setText((selection.cookies() == null ? "Public session" : selection.cookies().label())
                + (selection.proxy().isBlank() ? " · Direct connection" : " · " + selection.proxy()));
        }
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
