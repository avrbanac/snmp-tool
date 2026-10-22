package hr.avrbanac.snmptool.app.mibs;

import hr.avrbanac.snmptool.core.mib.repo.ImportPlan;
import hr.avrbanac.snmptool.core.mib.repo.ImportPlan.Candidate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Lets the user review an {@link ImportPlan} and choose which modules to import.
 */
final class ImportDialog {

    private final ImportPlan plan;
    private final Dialog<Set<String>> dialog = new Dialog<>();
    private final TableView<Row> table = new TableView<>();
    private final TextArea missing = new TextArea();
    private final Label missingTitle = new Label();
    private final ButtonType importButton = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);

    /** A candidate with its check box state. */
    private static final class Row {
        final Candidate candidate;
        final BooleanProperty selected;

        Row(Candidate candidate) {
            this.candidate = candidate;
            this.selected = new SimpleBooleanProperty(candidate.selectedByDefault());
        }
    }

    private ImportDialog(ImportPlan plan, Window owner) {
        this.plan = plan;
        dialog.initOwner(owner);
        dialog.setTitle("Import MIBs");
        dialog.setResizable(true);
        dialog.setHeaderText(header());

        table.getItems().setAll(plan.candidates().stream().map(Row::new).toList());
        table.getColumns().setAll(List.of(
                checkColumn(),
                column("Module", 200, r -> r.candidate.module()),
                column("Status", 200, r -> status(r.candidate)),
                column("Revision", 190, r -> revision(r.candidate)),
                column("Problems", 130, r -> problems(r.candidate)),
                column("File", 280, r -> r.candidate.file().toString())));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No MIB modules found"));
        table.getItems().forEach(r -> r.selected.addListener((_, _, _) -> updateSelection()));
        VBox.setVgrow(table, Priority.ALWAYS);

        Hyperlink all = new Hyperlink("Select all");
        all.setOnAction(_ -> table.getItems().stream().filter(r -> r.candidate.importable())
                .forEach(r -> r.selected.set(true)));
        Hyperlink none = new Hyperlink("Select none");
        none.setOnAction(_ -> table.getItems().forEach(r -> r.selected.set(false)));

        missingTitle.getStyleClass().add("details-key");
        missing.setEditable(false);
        missing.setPrefRowCount(4);
        missing.setWrapText(true);
        missing.managedProperty().bind(missing.visibleProperty());
        missingTitle.managedProperty().bind(missingTitle.visibleProperty());

        VBox content = new VBox(6, table, new HBox(4, all, none), missingTitle, missing);
        if (!plan.problems().isEmpty()) {
            TextArea problems = new TextArea(plan.problems().stream()
                    .map(p -> p.file() + ": " + p.message())
                    .collect(Collectors.joining("\n")));
            problems.setEditable(false);
            problems.setPrefRowCount(4);
            TitledPane skipped = new TitledPane("Skipped (" + plan.problems().size() + ")", problems);
            skipped.setExpanded(plan.candidates().isEmpty());
            content.getChildren().add(skipped);
        }
        content.setPrefSize(1000, 520);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(importButton, ButtonType.CANCEL);
        dialog.setResultConverter(b -> b == importButton ? selected() : null);
        updateSelection();
    }

    /** Shows the dialog and returns the modules to import, or empty if cancelled. */
    static Optional<Set<String>> show(ImportPlan plan, Window owner) {
        return new ImportDialog(plan, owner).dialog.showAndWait().filter(s -> !s.isEmpty());
    }

    private String header() {
        long files = plan.candidates().stream().map(Candidate::file).distinct().count();
        long importable = plan.candidates().stream().filter(Candidate::importable).count();
        return "Found " + MibFormat.count(plan.candidates().size(), "module") + " in "
                + MibFormat.count(files, "file") + "; " + importable + " can be imported.\n"
                + "Modules with missing dependencies can still be imported; definitions that need them will not load.";
    }

    private Set<String> selected() {
        return table.getItems().stream()
                .filter(r -> r.selected.get() && r.candidate.importable())
                .map(r -> r.candidate.module())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void updateSelection() {
        Set<String> selected = selected();
        Button ok = (Button) dialog.getDialogPane().lookupButton(importButton);
        ok.setText(selected.isEmpty() ? "Import" : "Import " + MibFormat.count(selected.size(), "module"));
        ok.setDisable(selected.isEmpty());

        Map<String, List<String>> absent = plan.missingDependencies(selected);
        boolean any = !absent.isEmpty();
        missingTitle.setVisible(any);
        missing.setVisible(any);
        missingTitle.setText("Missing dependencies (not loaded and not selected)");
        missing.setText(absent.entrySet().stream()
                .map(e -> e.getKey() + " needs " + String.join(", ", e.getValue()))
                .collect(Collectors.joining("\n")));
    }

    private static String status(Candidate c) {
        String text = switch (c.change()) {
            case NEW -> "New";
            case NEWER -> "Newer revision";
            case OLDER -> "Older revision";
            case CHANGED -> "Changed";
            case IDENTICAL -> c.replacesBundled() ? "Same as bundled" : "Already imported";
        };
        return c.replacesBundled() && c.change() != ImportPlan.Change.IDENTICAL ? text + " (replaces bundled)" : text;
    }

    private static String revision(Candidate c) {
        String revision = MibFormat.revision(c.revision());
        String installed = MibFormat.revision(c.installedRevision());
        return switch (c.change()) {
            case NEWER, OLDER -> installed + " → " + revision;
            default -> revision;
        };
    }

    private static String problems(Candidate c) {
        if (c.errors() == 0 && c.warnings() == 0) {
            return "";
        }
        return c.errors() > 0
                ? MibFormat.count(c.errors(), "error") + ", " + MibFormat.count(c.warnings(), "warning")
                : MibFormat.count(c.warnings(), "warning");
    }

    private static TableColumn<Row, String> column(String title, double width, Function<Row, String> value) {
        TableColumn<Row, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(f -> new SimpleStringProperty(value.apply(f.getValue())));
        return column;
    }

    private static TableColumn<Row, Boolean> checkColumn() {
        TableColumn<Row, Boolean> column = new TableColumn<>("");
        column.setPrefWidth(36);
        column.setMaxWidth(36);
        column.setSortable(false);
        column.setCellValueFactory(f -> f.getValue().selected);
        column.setCellFactory(_ -> new TableCell<>() {
            private final CheckBox box = new CheckBox();

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                Row row = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (row == null) {
                    setGraphic(null);
                    return;
                }
                // The cell value is the row's selected property, so this runs again whenever it changes.
                box.setSelected(Boolean.TRUE.equals(item));
                box.setOnAction(_ -> row.selected.set(box.isSelected()));
                box.setDisable(!row.candidate.importable());
                setGraphic(box);
            }
        });
        return column;
    }
}
