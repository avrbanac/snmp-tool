package hr.avrbanac.snmptool.app.details;

import hr.avrbanac.snmptool.app.Clipboards;
import hr.avrbanac.snmptool.app.NodeKinds;
import hr.avrbanac.snmptool.core.mib.model.BaseType;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.model.MibModule;
import hr.avrbanac.snmptool.core.mib.model.MibNode;
import hr.avrbanac.snmptool.core.mib.model.MibType;
import hr.avrbanac.snmptool.core.mib.model.NodeKind;
import hr.avrbanac.snmptool.core.mib.model.OidTree;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Shows the full definition of the selected tree node.
 */
public final class DetailsPane {

    private final StackPane root = new StackPane();
    private final Label placeholder = new Label("Select a node in the MIB tree to see its definition.");
    private final BorderPane content = new BorderPane();
    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final ComboBox<MibNode> definitions = new ComboBox<>();
    private final ScrollPane gridScroll = new ScrollPane();
    private final GridPane grid = newGrid();
    private final Label descriptionTitle = new Label("Description");
    private final TextArea description = new TextArea();
    private MibModel model;
    private OidTree.Node node;
    private boolean updatingChoice;

    public DetailsPane() {
        placeholder.getStyleClass().add("placeholder");
        placeholder.setWrapText(true);

        title.getStyleClass().add("details-title");
        subtitle.getStyleClass().add("details-subtitle");
        Button copyName = new Button("Copy name");
        copyName.setOnAction(_ -> current().ifPresent(d -> Clipboards.copy(d.name())));
        Button copyOid = new Button("Copy OID");
        copyOid.setOnAction(_ -> Optional.ofNullable(node).ifPresent(n -> Clipboards.copy(n.oid().toString())));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox titleRow = new HBox(8, title, spacer, copyName, copyOid);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        definitions.setCellFactory(_ -> new DefinitionCell());
        definitions.setButtonCell(new DefinitionCell());
        definitions.managedProperty().bind(definitions.visibleProperty());
        definitions.valueProperty().addListener((_, _, d) -> {
            if (!updatingChoice && d != null) {
                showDefinition(d);
            }
        });
        VBox header = new VBox(4, titleRow, subtitle, definitions);
        header.getStyleClass().add("details-header");

        gridScroll.setContent(grid);
        gridScroll.setFitToWidth(true);
        // The scroll pane skin caches its viewport as a bitmap, which was seen to keep showing the previous
        // definition's rows after an update; the grid is small, so caching gains nothing.
        gridScroll.skinProperty().addListener((_, _, skin) -> {
            if (skin != null) {
                Node viewport = gridScroll.lookup(".viewport");
                if (viewport != null) {
                    viewport.setCache(false);
                }
            }
        });

        descriptionTitle.getStyleClass().add("details-key");
        description.setEditable(false);
        description.setWrapText(true);
        description.getStyleClass().add("description");
        description.setPromptText("No description");
        VBox.setVgrow(description, Priority.ALWAYS);
        VBox descriptionBox = new VBox(4, descriptionTitle, description);

        SplitPane split = new SplitPane(gridScroll, descriptionBox);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.6);
        content.setTop(header);
        content.setCenter(split);

        root.getStyleClass().add("pane");
        root.getChildren().setAll(placeholder);
    }

    public Parent root() {
        return root;
    }

    private static GridPane newGrid() {
        GridPane g = new GridPane();
        g.getStyleClass().add("details-grid");
        ColumnConstraints keys = new ColumnConstraints();
        keys.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        values.setFillWidth(true);
        g.getColumnConstraints().addAll(keys, values);
        return g;
    }

    public void setModel(MibModel model) {
        this.model = model;
    }

    /** Shows a tree node; {@code null} clears the pane. */
    public void show(OidTree.Node treeNode) {
        node = treeNode;
        if (treeNode == null) {
            root.getChildren().setAll(placeholder);
            return;
        }
        root.getChildren().setAll(content);
        List<MibNode> defs = treeNode.definitions();
        updatingChoice = true;
        definitions.getItems().setAll(defs);
        definitions.setVisible(defs.size() > 1);
        if (!defs.isEmpty()) {
            definitions.getSelectionModel().selectFirst();
        }
        updatingChoice = false;
        if (defs.isEmpty()) {
            showUnnamed(treeNode);
        } else {
            showDefinition(defs.getFirst());
        }
    }

    private Optional<MibNode> current() {
        return Optional.ofNullable(definitions.getValue()).filter(_ -> node != null && !node.definitions().isEmpty());
    }

    private void showUnnamed(OidTree.Node treeNode) {
        title.setText(treeNode.label());
        subtitle.setText("No loaded module defines this object identifier");
        Rows rows = new Rows();
        rows.add("OID", treeNode.oid().toString());
        rows.add("Path", treeNode.symbolicPath());
        rows.apply();
        setDescription(Optional.empty());
    }

    private void showDefinition(MibNode d) {
        title.setText(d.name());
        subtitle.setText(NodeKinds.label(d.kind()) + (d.module().isEmpty() ? " · built-in" : " · " + d.module()));

        Rows rows = new Rows();
        rows.add("OID", d.oid().toString());
        rows.add("Path", node.symbolicPath());
        rows.add("Module", d.module());
        rows.add("Status", d.status());
        d.syntax().ifPresent(t -> addSyntax(rows, d, t));
        rows.add(d.kind() == NodeKind.TRAP || isV1(d) ? "Access" : "Max access", d.access());
        rows.add("Units", d.units());
        rows.add("Index", join(d.index().stream().map(MibNode.IndexItem::toString).toList()));
        rows.add("Augments", d.augments());
        rows.add("Default", d.defval());
        rows.add(membersLabel(d.kind()), join(d.members()));
        if (d.kind() == NodeKind.MODULE_IDENTITY) {
            addModule(rows, d.module());
        }
        rows.add("Reference", d.reference().map(TextFormat::unindent));
        rows.add("Source", d.position().map(p -> p.source() + ", line " + p.line()));
        rows.apply();

        setDescription(d.description());
    }

    private boolean isV1(MibNode d) {
        return model != null
                && model.module(d.module()).map(m -> m.smiVersion() == MibModule.SmiVersion.V1).orElse(false);
    }

    private static void addSyntax(Rows rows, MibNode d, MibType t) {
        rows.add("Syntax", t.syntaxText());
        String chain = typeChain(t);
        if (!chain.equals(t.syntaxText())) {
            rows.add("Type", chain);
        }
        rows.add("Display hint", t.displayHint());
        if (!t.namedValues().isEmpty()) {
            rows.add(t.baseType() == BaseType.BITS ? "Bits" : "Values",
                    join(t.namedValues().stream().map(MibType.NamedValue::toString).toList()));
        }
        rows.add("Size", join(t.sizes().stream().map(MibType.ValueRange::toString).toList(), " | "));
        rows.add("Range", join(t.ranges().stream().map(MibType.ValueRange::toString).toList(), " | "));
        if (d.kind() == NodeKind.ROW && !t.elements().isEmpty()) {
            rows.add("Columns", join(t.elements().stream().map(MibType.Element::name).toList()));
        }
    }

    /** E.g. {@code ShortName → DisplayString → OCTET STRING}. */
    private static String typeChain(MibType t) {
        List<String> names = new ArrayList<>();
        Optional<MibType> current = t.isNamed() ? Optional.of(t) : t.parent();
        while (current.isPresent()) {
            names.add(current.get().name());
            current = current.get().parent();
        }
        names.add(t.baseType().text());
        return names.stream().distinct().collect(Collectors.joining(" → "));
    }

    private void addModule(Rows rows, String moduleName) {
        if (model == null) {
            return;
        }
        model.module(moduleName).ifPresent(m -> {
            rows.add("Last updated", m.lastUpdated().map(TextFormat::smiDate));
            rows.add("Organization", m.organization().map(TextFormat::unindent));
            rows.add("Contact", m.contactInfo().map(TextFormat::unindent));
            rows.add("Revisions", join(m.revisions().stream().map(r -> TextFormat.smiDate(r.date())).toList(), "\n"));
            rows.add("Imports from", join(m.dependencies()));
            rows.add("File", m.source());
        });
    }

    private static String membersLabel(NodeKind kind) {
        return switch (kind) {
            case NOTIFICATION_GROUP -> "Notifications";
            case TRAP -> "Variables";
            default -> "Objects";
        };
    }

    private void setDescription(Optional<String> text) {
        description.setText(text.map(TextFormat::unindent).orElse(""));
        description.positionCaret(0);
        description.setScrollTop(0);
    }

    private static Optional<String> join(List<String> items) {
        return join(items, ", ");
    }

    private static Optional<String> join(List<String> items, String separator) {
        return items.isEmpty() ? Optional.empty() : Optional.of(String.join(separator, items));
    }

    /** Collects grid rows, skipping empty values. */
    private final class Rows {
        private final List<Label[]> rows = new ArrayList<>();

        void add(String key, Optional<String> value) {
            value.filter(v -> !v.isBlank()).ifPresent(v -> add(key, v));
        }

        void add(String key, String value) {
            if (value.isBlank()) {
                return;
            }
            Label k = new Label(key);
            k.getStyleClass().add("details-key");
            Label v = new Label(value);
            v.setWrapText(true);
            v.setMaxWidth(Double.MAX_VALUE);
            MenuItem copy = new MenuItem("Copy");
            copy.setOnAction(_ -> Clipboards.copy(value));
            v.setContextMenu(new ContextMenu(copy));
            rows.add(new Label[] {k, v});
        }

        void apply() {
            grid.getChildren().clear();
            for (int i = 0; i < rows.size(); i++) {
                GridPane.setValignment(rows.get(i)[0], VPos.TOP);
                grid.addRow(i, rows.get(i)[0], rows.get(i)[1]);
            }
        }
    }

    /** Shows {@code MODULE::name} in the definition chooser. */
    private static final class DefinitionCell extends ListCell<MibNode> {
        @Override
        protected void updateItem(MibNode item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : "Defined in " + item.module());
        }
    }
}
