package hr.avrbanac.snmptool.app;

import javafx.geometry.Orientation;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Main window layout: MIB tree (left), details (right), operations and results (bottom).
 *
 * <p>In milestone 1 the panes are placeholders; later milestones replace them with real views.
 */
final class MainWindow {

    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final Label status = new Label("Ready");

    MainWindow(Stage stage) {
        this.stage = stage;
        root.setTop(createMenuBar());
        root.setCenter(createContent());
        root.setBottom(createStatusBar());
    }

    Parent root() {
        return root;
    }

    private MenuBar createMenuBar() {
        MenuItem importMibs = new MenuItem("Import MIBs…");
        importMibs.setDisable(true);
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(_ -> stage.close());
        Menu file = new Menu("File", null, importMibs, new SeparatorMenuItem(), exit);

        MenuItem about = new MenuItem("About");
        about.setOnAction(_ -> showAbout());
        Menu help = new Menu("Help", null, about);

        return new MenuBar(file, help);
    }

    private Parent createContent() {
        SplitPane browser = new SplitPane(createTreePane(), createDetailsPane());
        browser.setDividerPositions(0.35);

        SplitPane content = new SplitPane(browser, createOperationsPane());
        content.setOrientation(Orientation.VERTICAL);
        content.setDividerPositions(0.7);
        return content;
    }

    private Parent createTreePane() {
        TextField search = new TextField();
        search.setPromptText("Search name or OID");
        search.setDisable(true);

        TreeView<String> tree = new TreeView<>(new TreeItem<>("No MIBs loaded"));
        VBox.setVgrow(tree, Priority.ALWAYS);

        VBox pane = new VBox(search, tree);
        pane.getStyleClass().add("pane");
        return pane;
    }

    private Parent createDetailsPane() {
        return placeholder("Select a node in the MIB tree to see its definition.");
    }

    private Parent createOperationsPane() {
        return placeholder("SNMP operations (get, getNext, walk, set) will appear here.");
    }

    private Parent createStatusBar() {
        HBox bar = new HBox(status);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private static Parent placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("placeholder");
        label.setWrapText(true);
        StackPane pane = new StackPane(label);
        pane.getStyleClass().add("pane");
        return pane;
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
