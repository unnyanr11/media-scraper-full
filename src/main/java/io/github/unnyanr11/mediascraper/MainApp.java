package io.github.unnyanr11.mediascraper;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Node;
import javafx.scene.input.TransferMode;
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
import java.util.Set;
import javafx.scene.input.Clipboard;

public class MainApp extends Application {
    private final javafx.collections.ObservableList<MediaItem> discovered = FXCollections.observableArrayList();
    private final AtomicReference<ScanService> current = new AtomicReference<>();
    private CookieInput cookieInput;
    private final Map<String, CookieInput> pastedCookies = new HashMap<>();
    private Path downloadPath;
    private Path rulesPath;
    private final javafx.collections.ObservableList<String> queuedUrls = FXCollections.observableArrayList();
    private final TextField importFilter = new TextField();
    private final NetworkPane networkPane = new NetworkPane();
    private final Map<String, SessionSelection> sessions = new HashMap<>();

    private record SessionSelection(CookieInput cookies, String proxy) {}
    private record ScanTask(String source, TaskOptions options, ScanService.Mode mode) {}

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
        Button login = new Button("Sign in with browser…");
        login.setOnAction(e -> {
            try {
                String address = ScanService.webUri(url.getText().strip()).toString();
                BrowserLogin.open(stage, address, new TaskOptions(cookieInput, proxy.getText().strip(), rulesPath,
                    networkPane.settings()), input -> {
                    cookieInput = input;
                    cookies.setText("Browser session");
                    status.setText("Browser session ready");
                }, status);
            } catch (IllegalArgumentException ex) {
                status.setText(ex.getMessage());
            }
        });
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
        var mode = new ComboBox<>(FXCollections.observableArrayList(ScanService.Mode.values()));
        mode.setValue(ScanService.Mode.AUTO);
        var mediaType = new ComboBox<>(FXCollections.observableArrayList("All links", "Images", "Videos and HLS",
            "Files and audio", "Hyperlinks"));
        mediaType.setValue("All links");
        FlowPane crawlerSettings = new FlowPane(8, 6, mode, new Label("Max pages"), pageLimit, mediaType,
            urlFilter, extensionFilter);
        var visibleResults = new FilteredList<>(discovered);
        TextField resultsFilter = new TextField();
        resultsFilter.setPromptText("Filter displayed links, titles, or types");
        resultsFilter.textProperty().addListener((obs, old, value) -> {
            String keyword = value.strip().toLowerCase(Locale.ROOT);
            visibleResults.setPredicate(item -> (item.url() + " " + item.caption() + " " + item.type())
                .toLowerCase(Locale.ROOT).contains(keyword));
        });
        var sortedResults = new javafx.collections.transformation.SortedList<>(visibleResults);
        TableView<MediaItem> table = new TableView<>(sortedResults);
        sortedResults.comparatorProperty().bind(table.comparatorProperty());
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
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
        java.util.function.BiConsumer<Boolean, List<ScanStore.SavedSource>> startScan = (incremental, saved) -> {
            try {
                pageLimit.commitValue();
                workers.commitValue();
                if ((downloadAll.isSelected() || archive.isSelected()) && downloadPath == null) {
                    status.setText("Select an output folder for downloads or archives");
                    return;
                }
                NetworkSettings network = networkPane.settings();
                Set<String> types = switch (mediaType.getValue()) {
                    case "Images" -> Set.of("image");
                    case "Videos and HLS" -> Set.of("video", "hls");
                    case "Files and audio" -> Set.of("file");
                    case "Hyperlinks" -> Set.of("page", "post");
                    default -> Set.of();
                };
                var settings = new ScanService.Settings(downloadPath, downloadAll.isSelected(), archive.isSelected(),
                    incremental, pageLimit.getValue(), urlFilter.getText().strip().toLowerCase(Locale.ROOT),
                    ScanService.Settings.extensions(extensionFilter.getText()), parallel.isSelected() ? workers.getValue() : 1,
                    types, mode.getValue());
                var batch = new ArrayList<ScanTask>();
                List<ScanStore.SavedSource> requested = saved;
                if (requested.isEmpty()) {
                    List<String> addresses = queuedUrls.isEmpty() ? List.of(url.getText().strip()) : List.copyOf(queuedUrls);
                    requested = addresses.stream().map(address -> new ScanStore.SavedSource(address, "", mode.getValue())).toList();
                }
                for (var item : requested) {
                    ScanService.webUri(item.url());
                    SessionSelection selection = sessions.getOrDefault(item.url(), new SessionSelection(cookieInput, proxy.getText()));
                    batch.add(new ScanTask(item.url(), new TaskOptions(selection.cookies(), selection.proxy().strip(), rulesPath, network),
                        item.mode()));
                }
                // Keep previous task credentials available for pending downloads until the app closes.
                for (ScanTask task : batch) {
                    CookieInput input = task.options().cookies();
                    if (input != null && input.pasted() != null) pastedCookies.put(input.reference(), input);
                }
                discovered.clear();
                startWorker(scan, update, resume, stop, pause, status, network, (service, report) -> {
                    for (int i = 0; i < batch.size() && !service.isStopped(); i++) {
                        ScanTask task = batch.get(i);
                        String prefix = "[" + (i + 1) + "/" + batch.size() + "] ";
                        report.accept(prefix + "Starting scan");
                        var buffer = new ArrayList<MediaItem>(128);
                        service.scan(task.source(), task.options(), settings.withMode(task.mode()), item -> {
                            buffer.add(item);
                            if (buffer.size() == 128) publishResults(buffer);
                        }, message -> report.accept(prefix + message));
                        publishResults(buffer);
                    }
                });
            } catch (IllegalArgumentException ex) {
                status.setText(ex.getMessage());
            }
        };
        scan.setOnAction(e -> startScan.accept(false, List.of()));
        update.setOnAction(e -> startScan.accept(true, List.of()));
        pause.setOnAction(e -> {
            ScanService service = current.get();
            if (service != null) service.pauseDownloads();
            status.setText("Downloads pause now or after the active scan. The queue is saved.");
        });
        resume.setOnAction(e -> {
            try {
                workers.commitValue();
                int count = parallel.isSelected() ? workers.getValue() : 1;
                startWorker(scan, update, resume, stop, pause, status, networkPane.settings(),
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
            for (MediaItem item : sortedResults) csv.append(quote(item.type())).append(',')
                .append(quote(item.postUrl())).append(',').append(quote(item.url())).append('\n');
            try { Files.writeString(file.toPath(), csv.toString()); status.setText("Exported " + sortedResults.size() + " links"); }
            catch (IOException ex) { status.setText("Export failed: " + ex.getMessage()); }
        });
        exportJson.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setInitialFileName("media-links.json");
            var file = chooser.showSaveDialog(stage);
            if (file == null) return;
            try {
                JsonExporter.write(file.toPath(), List.copyOf(sortedResults));
                status.setText("Exported " + sortedResults.size() + " entries as JSON");
            } catch (IOException ex) { status.setText("JSON export failed: " + ex.getMessage()); }
        });
        Button exportText = new Button("Export URL list");
        exportText.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setInitialFileName("media-links.txt");
            var file = chooser.showSaveDialog(stage);
            if (file == null) return;
            try {
                Files.write(file.toPath(), sortedResults.stream().map(MediaItem::url).distinct().toList());
                status.setText("Exported URL list");
            } catch (IOException ex) { status.setText("Export failed: " + ex.getMessage()); }
        });
        Button queueFiles = new Button("Queue selected files");
        queueFiles.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(table.getSelectionModel().getSelectedItems()));
        queueFiles.setOnAction(e -> {
            if (downloadPath == null) { status.setText("Choose a download folder on the Scan screen"); return; }
            try {
                var selected = List.copyOf(table.getSelectionModel().getSelectedItems());
                var network = networkPane.settings();
                Path folder = downloadPath;
                var options = new TaskOptions(cookieInput, proxy.getText().strip(), rulesPath, network);
                if (cookieInput != null && cookieInput.pasted() != null) pastedCookies.put(cookieInput.reference(), cookieInput);
                startWorker(scan, update, resume, stop, pause, status, network,
                    (service, report) -> service.queueDownloads(selected, folder, options, report));
            } catch (IllegalArgumentException ex) { status.setText(ex.getMessage()); }
        });
        importFilter.setPromptText("Optional URL filter for clipboard, file, or dropped text");
        ListView<String> queued = new ListView<>(queuedUrls);
        queued.setPlaceholder(new Label("Drop URLs here or import them"));
        queued.setPrefHeight(175);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab scanTab = tab("Scan", page(new Label("Source URL"), url, crawlerSettings,
            new FlowPane(8, 6, paste, pick, login, clear, cookies, proxy), new HBox(8, chooseFolder, folderLabel),
            new FlowPane(8, 6, downloadAll, archive, rules, rulesLabel), new HBox(8, scan, update, stop)));
        Tab batches = tab("Batches", batchesPage(queued, queueStatus, status, url, mode, startScan,
            new FlowPane(8, 6, clipboard, importFile, sequence, savedSources, configureQueue, clearQueue)));
        VBox results = page(resultsFilter, new FlowPane(8, 6, history, queueFiles, export, exportJson, exportText, backup), table);
        VBox.setVgrow(table, Priority.ALWAYS);
        Tab resultsTab = tab("Results", results);
        Tab downloads = tab("Downloads", downloadsPage(status, pause, resume, parallel, workers));
        Tab settingsTab = tab("Settings", networkPane);
        tabs.getTabs().addAll(scanTab, batches, resultsTab, downloads, settingsTab);
        stage.setTitle("Media Scraper Full");
        status.setPadding(new Insets(8, 14, 8, 14));
        var root = new BorderPane(tabs);
        root.setBottom(status);
        stage.setScene(new Scene(root, 960, 660));
        stage.setMinWidth(720);
        stage.setMinHeight(520);
        stage.show();
    }

    private void publishResults(List<MediaItem> buffer) {
        if (buffer.isEmpty()) return;
        var results = List.copyOf(buffer);
        buffer.clear();
        Platform.runLater(() -> discovered.addAll(results));
    }

    private void startWorker(Button scan, Button update, Button resume, Button stop, Button pause, Label status, NetworkSettings network,
                             java.util.function.BiConsumer<ScanService, java.util.function.Consumer<String>> work) {
        ScanService service = new ScanService(null, pastedCookies, network);
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

    private VBox batchesPage(ListView<String> queued, Label queueStatus, Label status, TextField source,
                             ComboBox<ScanService.Mode> mode,
                             java.util.function.BiConsumer<Boolean, List<ScanStore.SavedSource>> startScan, Node imports) {
        ComboBox<String> categories = new ComboBox<>();
        categories.setPromptText("All categories");
        var saved = new TableView<ScanStore.SavedSource>();
        var name = column("Source URL", ScanStore.SavedSource::url, 490);
        var category = column("Category", ScanStore.SavedSource::category, 145);
        var kind = column("Mode", (ScanStore.SavedSource item) -> item.mode().toString(), 175);
        saved.getColumns().setAll(List.of(name, category, kind));
        Runnable load = () -> {
            try (ScanStore store = new ScanStore()) {
                String selected = categories.getValue();
                var entries = store.savedSources();
                var names = new ArrayList<String>();
                names.add("All categories");
                names.addAll(entries.stream().map(ScanStore.SavedSource::category).filter(text -> !text.isBlank())
                    .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList());
                if (!categories.getItems().equals(names)) categories.getItems().setAll(names);
                if (selected != null && names.contains(selected)) categories.setValue(selected);
                saved.getItems().setAll(entries.stream().filter(item -> selected == null || selected.equals("All categories")
                    || item.category().equals(selected)).toList());
            } catch (Exception e) { status.setText("Could not load saved batches: " + e.getMessage()); }
        };
        categories.setOnAction(e -> load.run());
        Button refresh = new Button("Refresh saved batches");
        refresh.setOnAction(e -> load.run());
        Button save = new Button("Set category…");
        save.setOnAction(e -> {
            var selected = saved.getSelectionModel().getSelectedItem();
            String address = selected == null ? source.getText().strip() : selected.url();
            try { ScanService.webUri(address); }
            catch (IllegalArgumentException ex) { status.setText("Select a saved source or enter a URL on Scan"); return; }
            var dialog = new TextInputDialog(selected == null ? "" : selected.category());
            dialog.setTitle("Batch category");
            dialog.setHeaderText("Enter a category or leave it blank");
            dialog.showAndWait().ifPresent(value -> {
                try (ScanStore store = new ScanStore()) {
                    store.saveSource(address, value.strip(), selected == null ? mode.getValue() : selected.mode());
                    load.run();
                } catch (Exception ex) { status.setText("Could not save batch category"); }
            });
        });
        Button runQueued = new Button("Run queued scans");
        runQueued.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(queuedUrls));
        runQueued.setOnAction(e -> startScan.accept(false, List.of()));
        Button updateSaved = new Button("Update saved batches");
        updateSaved.setOnAction(e -> {
            if (saved.getItems().isEmpty()) { status.setText("No saved batches in this category"); return; }
            startScan.accept(true, List.copyOf(saved.getItems()));
        });
        Button updateSelected = new Button("Update selected");
        updateSelected.setOnAction(e -> {
            var item = saved.getSelectionModel().getSelectedItem();
            if (item != null) startScan.accept(true, List.of(item));
        });
        saved.getSelectionModel().selectedItemProperty().addListener((obs, before, item) -> {
            if (item != null) { source.setText(item.url()); mode.setValue(item.mode()); }
        });
        queued.setOnDragOver(e -> {
            if (e.getDragboard().hasUrl() || e.getDragboard().hasString() || e.getDragboard().hasFiles())
                e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        queued.setOnDragDropped(e -> {
            var board = e.getDragboard();
            var links = new ArrayList<String>();
            if (board.hasUrl()) links.addAll(UrlSources.fromText(board.getUrl()));
            if (board.hasString()) links.addAll(UrlSources.fromText(board.getString()));
            if (board.hasFiles()) for (var file : board.getFiles()) {
                try { links.addAll(UrlSources.fromFile(file.toPath())); }
                catch (IOException ex) { status.setText("Unable to read dropped file"); }
            }
            addQueued(links, queueStatus);
            e.setDropCompleted(!links.isEmpty());
            e.consume();
        });
        load.run();
        var page = page(importFilter, imports, new HBox(8, queueStatus, runQueued), queued, new FlowPane(8, 6, categories, refresh, save,
            updateSaved, updateSelected), saved);
        VBox.setVgrow(saved, Priority.ALWAYS);
        return page;
    }

    private VBox downloadsPage(Label status, Button pause, Button resume, CheckBox parallel, Spinner<Integer> workers) {
        var transfers = new TableView<ScanStore.Transfer>();
        transfers.getColumns().setAll(List.of(column("File URL", job -> job.item().url(), 520),
            column("Destination", job -> job.folder().toString(), 240),
            column("Status", job -> job.options().needsPastedCookies() ? "Cookies required" : "Pending", 115)));
        Button refresh = new Button("Refresh queue");
        Runnable reload = () -> {
            try (ScanStore store = new ScanStore()) {
                transfers.getItems().setAll(store.pending(null, null, pastedCookies));
            } catch (Exception e) { status.setText("Could not load download queue"); }
        };
        refresh.setOnAction(e -> reload.run());
        resume.disableProperty().addListener((obs, old, busy) -> { if (!busy) reload.run(); });
        var page = page(new FlowPane(8, 6, pause, resume, refresh, parallel, new Label("Workers"), workers), transfers);
        VBox.setVgrow(transfers, Priority.ALWAYS);
        reload.run();
        return page;
    }

    private static <T> TableColumn<T, String> column(String title, java.util.function.Function<T, String> display,
                                                      double width) {
        var column = new TableColumn<T, String>(title);
        column.setCellValueFactory(value -> new javafx.beans.property.SimpleStringProperty(display.apply(value.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private static VBox page(Node... children) {
        var page = new VBox(10, children);
        page.setPadding(new Insets(14));
        return page;
    }

    private static Tab tab(String title, Node content) {
        return new Tab(title, content);
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
        String filter = importFilter.getText().strip().toLowerCase(Locale.ROOT);
        additional.stream().filter(url -> url.toLowerCase(Locale.ROOT).contains(filter)).forEach(combined::add);
        queuedUrls.clear();
        queuedUrls.addAll(combined);
        label.setText(queuedUrls.size() + " URLs queued");
    }

    private static String quote(String value) {return "\"" + value.replace("\"", "\"\"") + "\"";}
    public static void main(String[] args) {launch(args);}
}
