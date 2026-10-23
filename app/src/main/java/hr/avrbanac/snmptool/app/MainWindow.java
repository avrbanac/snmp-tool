package hr.avrbanac.snmptool.app;

import hr.avrbanac.snmptool.app.details.DetailsPane;
import hr.avrbanac.snmptool.app.mibs.MibController;
import hr.avrbanac.snmptool.app.mibs.ModulesWindow;
import hr.avrbanac.snmptool.app.ops.OperationsPanel;
import hr.avrbanac.snmptool.app.tree.MibTreeView;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.repo.MibRepository;
import hr.avrbanac.snmptool.core.settings.SavedDevice;
import hr.avrbanac.snmptool.core.settings.Settings;
import hr.avrbanac.snmptool.core.settings.SettingsStore;
import hr.avrbanac.snmptool.core.snmp.DeviceProfile;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

/**
 * Main window layout: MIB tree (left), details (right), operations and results (bottom).
 */
final class MainWindow {

    private static final System.Logger LOG = System.getLogger(MainWindow.class.getName());
    private static final KeyCombination FIND = new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination IMPORT = new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN);

    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final Label status = new Label();
    private final Hyperlink problems = new Hyperlink();
    private final MibTreeView treeView = new MibTreeView();
    private final DetailsPane detailsPane = new DetailsPane();
    private final MibController mibs = new MibController(new MibRepository(MibRepository.defaultDirectory()));
    private final OperationsPanel operations = new OperationsPanel(treeView::showOid, this::saveSettings);
    private final SettingsStore settingsStore = new SettingsStore(SettingsStore.defaultFile());
    private Settings settings = Settings.empty();
    private ModulesWindow modulesWindow;

    MainWindow(Stage stage) {
        this.stage = stage;
        root.setTop(createMenuBar());
        root.setCenter(createContent());
        root.setBottom(createStatusBar());
        root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (FIND.match(e)) {
                treeView.focusSearch();
                e.consume();
            }
        });
        treeView.selectedNodeProperty().addListener((_, _, node) -> {
            detailsPane.show(node);
            operations.setSelectedNode(node);
        });
        stage.setOnHidden(_ -> operations.close());
        mibs.modelProperty().addListener((_, _, model) -> showModel(model));
        status.textProperty().bind(mibs.statusProperty());
        problems.setOnAction(_ -> showModules(true));
        problems.setVisible(false);
        loadSettings();
        mibs.reload("");
    }

    Parent root() {
        return root;
    }

    private void showModel(MibModel model) {
        detailsPane.setModel(model);
        operations.setModel(model);
        treeView.setModel(model, mibs.search());
        problems.setText(MibController.problems(model.diagnostics()));
        problems.setVisible(true);
    }

    /** Reads the settings file (small, so on the JavaFX thread); a first start gets a localhost device. */
    private void loadSettings() {
        List<String> messages = new ArrayList<>();
        try {
            SettingsStore.Loaded loaded = settingsStore.load();
            settings = loaded.settings();
            messages.addAll(loaded.problems());
            if (!loaded.existed()) {
                settings = settings.withDevices(List.of(new SavedDevice(
                        DeviceProfile.v2c("localhost", "127.0.0.1", "public", "private"), true)), Optional.empty());
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot read " + settingsStore.file(), e);
            messages.add("Cannot read " + settingsStore.file() + ": " + e.getMessage());
        }
        operations.setDevices(settings.devices(), settings.selectedDevice());
        if (!messages.isEmpty()) {
            Platform.runLater(() -> warn("Problems in the settings file", String.join("\n", messages)));
        }
    }

    /** Writes the current devices to the settings file; called whenever they change. */
    private void saveSettings() {
        settings = settings.withDevices(operations.savedDevices(), operations.selectedDevice());
        try {
            settingsStore.save(settings);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot write " + settingsStore.file(), e);
            warn("Cannot save settings", "Cannot write " + settingsStore.file() + ": " + e.getMessage());
        }
    }

    private void warn(String header, String text) {
        Alert alert = new Alert(Alert.AlertType.WARNING, text);
        alert.initOwner(stage);
        alert.setTitle("Settings");
        alert.setHeaderText(header);
        alert.show();
    }

    private void showModules(boolean showProblems) {
        if (modulesWindow == null) {
            modulesWindow = new ModulesWindow(mibs, stage, SnmpToolApp.stylesheet());
        }
        modulesWindow.show(showProblems);
    }

    private MenuBar createMenuBar() {
        MenuItem importFiles = new MenuItem("Import MIB files…");
        importFiles.setAccelerator(IMPORT);
        importFiles.setOnAction(_ -> mibs.importFiles(stage));
        importFiles.disableProperty().bind(mibs.busyProperty());
        MenuItem importFolder = new MenuItem("Import MIB folder…");
        importFolder.setOnAction(_ -> mibs.importFolder(stage));
        importFolder.disableProperty().bind(mibs.busyProperty());
        MenuItem modules = new MenuItem("MIB modules…");
        modules.setOnAction(_ -> showModules(false));
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(_ -> stage.close());
        Menu file = new Menu("File", null, importFiles, importFolder, new SeparatorMenuItem(), modules,
                new SeparatorMenuItem(), exit);

        MenuItem find = new MenuItem("Find…");
        find.setAccelerator(FIND);
        find.setOnAction(_ -> treeView.focusSearch());
        Menu edit = new Menu("Edit", null, find);

        MenuItem about = new MenuItem("About");
        about.setOnAction(_ -> showAbout());
        Menu help = new Menu("Help", null, about);

        return new MenuBar(file, edit, help);
    }

    private Parent createContent() {
        SplitPane browser = new SplitPane(treeView.root(), detailsPane.root());
        browser.setDividerPositions(0.35);

        SplitPane content = new SplitPane(browser, operations.root());
        content.setOrientation(Orientation.VERTICAL);
        content.setDividerPositions(0.6);
        return content;
    }

    private Parent createStatusBar() {
        HBox bar = new HBox(4, status, new Label("·"), problems);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        bar.getChildren().get(1).visibleProperty().bind(problems.visibleProperty());
        return bar;
    }

    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setTitle("About");
        alert.setHeaderText(SnmpToolApp.TITLE);
        alert.setContentText("MIB browser with basic SNMP operations.");
        alert.showAndWait();
    }
}
