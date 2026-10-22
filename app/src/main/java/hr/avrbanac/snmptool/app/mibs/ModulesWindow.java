package hr.avrbanac.snmptool.app.mibs;

import hr.avrbanac.snmptool.core.mib.model.Diagnostic;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.repo.ModuleEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Window listing the available MIB modules (with import and remove) and the problems found while loading.
 */
public final class ModulesWindow {

    private final MibController controller;
    private final Stage stage = new Stage();
    private final TabPane tabs = new TabPane();
    private final Tab modulesTab = new Tab("Modules");
    private final Tab problemsTab = new Tab("Problems");
    private final TableView<ModuleEntry> modules = new TableView<>();
    private final TextField moduleFilter = new TextField();
    private final Label moduleCount = new Label();
    private final TableView<Diagnostic> problems = new TableView<>();
    private final TextField problemFilter = new TextField();
    private final Label problemCount = new Label();
    private Map<String, List<Diagnostic>> problemsBySource = Map.of();

    public ModulesWindow(MibController controller, Window owner, String stylesheet) {
        this.controller = controller;
        stage.initOwner(owner);
        stage.setTitle("MIB Modules");

        modulesTab.setContent(createModulesTab());
        modulesTab.setClosable(false);
        problemsTab.setContent(createProblemsTab());
        problemsTab.setClosable(false);
        tabs.getTabs().setAll(modulesTab, problemsTab);

        Scene scene = new Scene(tabs, 1100, 600);
        scene.getStylesheets().add(stylesheet);
        stage.setScene(scene);

        controller.modulesProperty().addListener((_, _, _) -> refresh());
        controller.modelProperty().addListener((_, _, _) -> refresh());
        refresh();
    }

    /** Shows the window with the modules or the problems tab selected. */
    public void show(boolean showProblems) {
        tabs.getSelectionModel().select(showProblems ? problemsTab : modulesTab);
        stage.show();
        stage.toFront();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Modules tab

    private VBox createModulesTab() {
        modules.getColumns().setAll(List.of(
                column("Module", 230, ModuleEntry::name),
                column("Origin", 190, ModulesWindow::origin),
                column("Revision", 90, e -> MibFormat.revision(e.revision())),
                column("Problems", 130, e -> problemText(e.source())),
                column("Imported", 130, e -> MibFormat.instant(e.importedAt())),
                column("Imported from", 300, e -> e.importedFrom().orElse(""))));
        modules.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        modules.setRowFactory(_ -> {
            TableRow<ModuleEntry> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    showProblemsOf(row.getItem());
                }
            });
            return row;
        });
        VBox.setVgrow(modules, Priority.ALWAYS);

        Button importFiles = new Button("Import files…");
        importFiles.setOnAction(_ -> controller.importFiles(stage));
        Button importFolder = new Button("Import folder…");
        importFolder.setOnAction(_ -> controller.importFolder(stage));
        Button remove = new Button("Remove");
        remove.setOnAction(_ -> selectedModule().ifPresent(e -> controller.remove(e, stage)));
        remove.disableProperty().bind(Bindings.createBooleanBinding(
                () -> controller.busyProperty().get() || selectedModule()
                        .map(e -> e.origin() != ModuleEntry.Origin.IMPORTED).orElse(true),
                controller.busyProperty(), modules.getSelectionModel().selectedItemProperty()));
        Button showProblems = new Button("Show problems");
        showProblems.setOnAction(_ -> selectedModule().ifPresent(this::showProblemsOf));
        showProblems.disableProperty().bind(modules.getSelectionModel().selectedItemProperty().isNull());
        importFiles.disableProperty().bind(controller.busyProperty());
        importFolder.disableProperty().bind(controller.busyProperty());

        HBox buttons = new HBox(6, importFiles, importFolder, remove, showProblems);
        buttons.getChildren().forEach(b -> ((Region) b).setMinWidth(Region.USE_PREF_SIZE));
        buttons.setAlignment(Pos.CENTER_LEFT);
        Label store = new Label("Imported modules are stored in " + controller.storeDirectory());
        store.getStyleClass().add("placeholder");

        moduleFilter.setPromptText("Filter modules");
        moduleFilter.textProperty().addListener((_, _, _) -> refreshModules());
        HBox.setHgrow(moduleFilter, Priority.ALWAYS);
        HBox top = new HBox(8, moduleFilter, moduleCount);
        top.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6, top, modules, buttons, store);
        box.getStyleClass().add("pane");
        return box;
    }

    private Optional<ModuleEntry> selectedModule() {
        return Optional.ofNullable(modules.getSelectionModel().getSelectedItem());
    }

    private void showProblemsOf(ModuleEntry entry) {
        problemFilter.setText(entry.source());
        tabs.getSelectionModel().select(problemsTab);
    }

    private static String origin(ModuleEntry e) {
        return switch (e.origin()) {
            case BUNDLED -> "Bundled";
            case IMPORTED -> e.overridesBundled() ? "Imported (replaces bundled)" : "Imported";
        };
    }

    private String problemText(String source) {
        List<Diagnostic> list = problemsBySource.getOrDefault(source, List.of());
        return list.isEmpty() ? "" : MibController.problems(list);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Problems tab

    private VBox createProblemsTab() {
        problems.getColumns().setAll(List.of(
                diagnosticColumn("Severity", 80, d -> d.severity() == Diagnostic.Severity.ERROR ? "Error" : "Warning"),
                diagnosticColumn("File", 220, d -> d.position().source()),
                diagnosticColumn("Line", 60, d -> Integer.toString(d.position().line())),
                diagnosticColumn("Message", 600, Diagnostic::message)));
        problems.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        problems.setPlaceholder(new Label("No problems"));
        VBox.setVgrow(problems, Priority.ALWAYS);

        problemFilter.setPromptText("Filter by file or message");
        problemFilter.textProperty().addListener((_, _, _) -> refreshProblems());
        HBox.setHgrow(problemFilter, Priority.ALWAYS);
        HBox top = new HBox(8, problemFilter, problemCount);
        top.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6, top, problems);
        box.getStyleClass().add("pane");
        return box;
    }

    private void refresh() {
        MibModel model = controller.modelProperty().get();
        Map<String, List<Diagnostic>> bySource = new HashMap<>();
        if (model != null) {
            for (Diagnostic d : model.diagnostics()) {
                bySource.computeIfAbsent(d.position().source(), _ -> new ArrayList<>()).add(d);
            }
        }
        problemsBySource = bySource;
        refreshModules();
        refreshProblems();
    }

    /** Imported modules first, then bundled ones, each by name; filtered by the filter text. */
    private void refreshModules() {
        List<ModuleEntry> all = controller.modulesProperty().get();
        String filter = moduleFilter.getText().strip().toLowerCase(Locale.ROOT);
        List<ModuleEntry> shown = all.stream()
                .filter(e -> filter.isEmpty() || e.name().toLowerCase(Locale.ROOT).contains(filter))
                .sorted(Comparator.comparing((ModuleEntry e) -> e.origin() != ModuleEntry.Origin.IMPORTED)
                        .thenComparing(ModuleEntry::name))
                .toList();
        ModuleEntry selected = modules.getSelectionModel().getSelectedItem();
        modules.getItems().setAll(shown);
        if (selected != null) {
            shown.stream().filter(e -> e.name().equals(selected.name())).findFirst()
                    .ifPresent(e -> modules.getSelectionModel().select(e));
        }
        modules.refresh();
        long imported = all.stream().filter(e -> e.origin() == ModuleEntry.Origin.IMPORTED).count();
        String counts = MibFormat.count(all.size(), "module") + " (" + imported + " imported)";
        moduleCount.setText(shown.size() == all.size() ? counts : shown.size() + " of " + counts);
    }

    private void refreshProblems() {
        MibModel model = controller.modelProperty().get();
        List<Diagnostic> all = model == null ? List.of() : model.diagnostics();
        String filter = problemFilter.getText().strip().toLowerCase(Locale.ROOT);
        List<Diagnostic> shown = filter.isEmpty() ? all : all.stream()
                .filter(d -> d.position().source().toLowerCase(Locale.ROOT).contains(filter)
                        || d.message().toLowerCase(Locale.ROOT).contains(filter))
                .toList();
        problems.getItems().setAll(shown);
        problemCount.setText(shown.size() == all.size() ? MibController.problems(all)
                : shown.size() + " of " + all.size() + " shown");
    }

    private static TableColumn<ModuleEntry, String> column(String title, double width,
            Function<ModuleEntry, String> value) {
        TableColumn<ModuleEntry, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(f -> new SimpleStringProperty(value.apply(f.getValue())));
        return column;
    }

    private static TableColumn<Diagnostic, String> diagnosticColumn(String title, double width,
            Function<Diagnostic, String> value) {
        TableColumn<Diagnostic, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(f -> new SimpleStringProperty(value.apply(f.getValue())));
        return column;
    }
}
