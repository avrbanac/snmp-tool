package hr.avrbanac.snmptool.app;

import hr.avrbanac.snmptool.app.details.DetailsPane;
import hr.avrbanac.snmptool.app.tree.MibTreeView;
import hr.avrbanac.snmptool.core.mib.BundledMibs;
import hr.avrbanac.snmptool.core.mib.MibLoader;
import hr.avrbanac.snmptool.core.mib.model.Diagnostic;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import javafx.concurrent.Task;
import javafx.geometry.Orientation;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
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
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Main window layout: MIB tree (left), details (right), operations and results (bottom).
 */
final class MainWindow {

    private static final KeyCombination FIND = new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN);

    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final Label status = new Label("Loading MIBs…");
    private final MibTreeView treeView = new MibTreeView();
    private final DetailsPane detailsPane = new DetailsPane();

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
        treeView.selectedNodeProperty().addListener((_, _, node) -> detailsPane.show(node));
        loadModel();
    }

    Parent root() {
        return root;
    }

    private void loadModel() {
        Task<MibModel> task = new Task<>() {
            @Override
            protected MibModel call() {
                return MibLoader.load(BundledMibs.ietf());
            }
        };
        task.setOnSucceeded(_ -> {
            MibModel model = task.getValue();
            detailsPane.setModel(model);
            treeView.setModel(model);
            status.setText(summary(model));
        });
        task.setOnFailed(_ -> status.setText("Loading MIBs failed: " + task.getException().getMessage()));
        Thread.ofPlatform().daemon().name("mib-loader").start(task);
    }

    private static String summary(MibModel model) {
        long errors = model.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).count();
        long warnings = model.diagnostics().size() - errors;
        String problems = errors == 0 && warnings == 0 ? "no problems"
                : errors + " errors, " + warnings + " warnings";
        return String.format("%d modules · %,d nodes · %s", model.modules().size(), model.tree().size(), problems);
    }

    private MenuBar createMenuBar() {
        MenuItem importMibs = new MenuItem("Import MIBs…");
        importMibs.setDisable(true);
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(_ -> stage.close());
        Menu file = new Menu("File", null, importMibs, new SeparatorMenuItem(), exit);

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

        SplitPane content = new SplitPane(browser, createOperationsPane());
        content.setOrientation(Orientation.VERTICAL);
        content.setDividerPositions(0.75);
        return content;
    }

    private Parent createOperationsPane() {
        Label label = new Label("SNMP operations (get, getNext, walk, set) will appear here.");
        label.getStyleClass().add("placeholder");
        label.setWrapText(true);
        StackPane pane = new StackPane(label);
        pane.getStyleClass().add("pane");
        return pane;
    }

    private Parent createStatusBar() {
        HBox bar = new HBox(status);
        bar.getStyleClass().add("status-bar");
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
