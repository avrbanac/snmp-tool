package hr.avrbanac.snmptool.app.tree;

import hr.avrbanac.snmptool.app.Clipboards;
import hr.avrbanac.snmptool.core.mib.MibSearch;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.model.MibNode;
import hr.avrbanac.snmptool.core.mib.model.Oid;
import hr.avrbanac.snmptool.core.mib.model.OidTree;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * The MIB tree with its search box.
 *
 * <p>Without a search the full tree is shown, built lazily as nodes are expanded. With a search the tree is
 * filtered to the matches and their ancestors (see {@link FilteredTree}), with matches in bold. From there,
 * "Show in full tree" (double-click or Enter) clears the search and selects the node in the full tree.
 */
public final class MibTreeView {

    /** Most matches shown in the filtered tree. */
    private static final int SEARCH_LIMIT = 2000;
    /** Expanded on first display: iso.org.dod.internet.mgmt.mib-2. */
    private static final Oid DEFAULT_EXPANDED = Oid.parse("1.3.6.1.2.1");
    private static final KeyCombination COPY = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);

    private final TextField search = new TextField();
    private final CheckBox inDescriptions = new CheckBox("Descriptions");
    private final Label matchCount = new Label();
    private final TreeView<OidTree.Node> tree = new TreeView<>();
    private final VBox root;
    private final ReadOnlyObjectWrapper<OidTree.Node> selected = new ReadOnlyObjectWrapper<>();
    private final Set<Oid> matches = new HashSet<>();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(250));
    private MibModel model;
    private MibSearch searchIndex;
    private boolean filtered;

    public MibTreeView() {
        search.setPromptText("Search name, MODULE::name or OID");
        search.textProperty().addListener((_, _, _) -> debounce.playFromStart());
        search.setOnAction(_ -> {
            debounce.stop();
            applySearch();
        });
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                search.clear();
            }
        });
        debounce.setOnFinished(_ -> applySearch());
        inDescriptions.setTooltip(new Tooltip("Also search description text"));
        inDescriptions.selectedProperty().addListener((_, _, _) -> applySearch());

        matchCount.getStyleClass().add("match-count");
        matchCount.managedProperty().bind(matchCount.visibleProperty());
        matchCount.setVisible(false);

        tree.setShowRoot(false);
        tree.setDisable(true);
        tree.setCellFactory(_ -> new MibTreeCell(matches::contains));
        tree.getSelectionModel().selectedItemProperty()
                .addListener((_, _, item) -> selected.set(item == null ? null : item.getValue()));
        tree.setContextMenu(createContextMenu());
        tree.setOnKeyPressed(e -> {
            if (COPY.match(e)) {
                selectedDefinition().ifPresent(d -> Clipboards.copy(d.oid().toString()));
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER && filtered) {
                showInFullTree();
                e.consume();
            }
        });
        tree.setOnMouseClicked(e -> {
            if (filtered && e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2
                    && selected.get() != null) {
                showInFullTree();
                e.consume();
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);

        HBox searchRow = new HBox(6, search, inDescriptions);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(search, Priority.ALWAYS);
        root = new VBox(6, searchRow, matchCount, tree);
        root.getStyleClass().add("pane");
    }

    public Parent root() {
        return root;
    }

    /** The node selected in the tree, or {@code null}. */
    public ReadOnlyObjectProperty<OidTree.Node> selectedNodeProperty() {
        return selected.getReadOnlyProperty();
    }

    /** Shows a new model with its search index, keeping the selection and search if possible. */
    public void setModel(MibModel model, MibSearch searchIndex) {
        this.model = model;
        this.searchIndex = searchIndex;
        tree.setDisable(false);
        applySearch();
    }

    public void focusSearch() {
        search.requestFocus();
        search.selectAll();
    }

    private ContextMenu createContextMenu() {
        MenuItem copyName = new MenuItem("Copy name");
        copyName.setOnAction(_ -> selectedDefinition().ifPresent(d -> Clipboards.copy(d.name())));
        MenuItem copyQualified = new MenuItem("Copy MODULE::name");
        copyQualified.setOnAction(_ -> selectedDefinition().ifPresent(d -> Clipboards.copy(d.qualifiedName())));
        MenuItem copyOid = new MenuItem("Copy OID");
        copyOid.setAccelerator(COPY);
        copyOid.setOnAction(_ -> Optional.ofNullable(selected.get()).ifPresent(n -> Clipboards.copy(n.oid().toString())));
        MenuItem showAll = new MenuItem("Show all children");
        showAll.setOnAction(_ -> selectedItem().ifPresent(FilteredTree::showAllChildren));
        MenuItem showInFullTree = new MenuItem("Show in full tree");
        showInFullTree.setOnAction(_ -> showInFullTree());
        ContextMenu menu = new ContextMenu(showInFullTree, showAll, new SeparatorMenuItem(), copyName, copyQualified,
                copyOid);
        menu.setOnShowing(_ -> {
            showInFullTree.setVisible(filtered);
            showAll.setVisible(filtered);
            showAll.setDisable(selectedItem().filter(FilteredTree::hasHiddenChildren).isEmpty());
        });
        return menu;
    }

    private Optional<TreeItem<OidTree.Node>> selectedItem() {
        return Optional.ofNullable(tree.getSelectionModel().getSelectedItem());
    }

    /** Clears the search and selects the current node in the full tree. */
    private void showInFullTree() {
        if (selected.get() == null) {
            return;
        }
        search.clear();
        debounce.stop();
        applySearch();
        tree.requestFocus();
    }

    private Optional<MibNode> selectedDefinition() {
        return Optional.ofNullable(selected.get()).flatMap(OidTree.Node::definition);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Search and tree content

    private void applySearch() {
        if (model == null) {
            return;
        }
        Optional<Oid> previous = Optional.ofNullable(selected.get()).map(OidTree.Node::oid);
        boolean wasFiltered = filtered;
        matches.clear();
        String query = search.getText();
        if (query.isBlank()) {
            filtered = false;
            matchCount.setVisible(false);
            showFullTree();
            if (previous.isPresent()) {
                reveal(previous.get(), true);
            } else if (!wasFiltered) {
                reveal(DEFAULT_EXPANDED, false);
            }
            return;
        }
        filtered = true;
        MibSearch.Result result = searchIndex.search(query, inDescriptions.isSelected(), SEARCH_LIMIT);
        result.nodes().forEach(n -> matches.add(n.oid()));
        tree.setRoot(FilteredTree.build(result.nodes()));
        int count = result.nodes().size();
        matchCount.setText(count == 0 ? "No matches"
                : count == 1 ? "1 match"
                : count + (result.truncated() ? "+" : "") + " matches");
        matchCount.setVisible(true);
        Optional<Oid> target = previous.filter(matches::contains)
                .or(() -> result.nodes().stream().findFirst().map(OidTree.Node::oid));
        target.ifPresent(oid -> reveal(oid, true));
    }

    private void showFullTree() {
        TreeItem<OidTree.Node> hiddenRoot = new TreeItem<>();
        model.tree().roots().forEach(n -> hiddenRoot.getChildren().add(new LazyTreeItem(n)));
        hiddenRoot.setExpanded(true);
        tree.setRoot(hiddenRoot);
    }

    /** Expands the path to the node with this OID (or as far as it exists) and optionally selects it. */
    private void reveal(Oid oid, boolean select) {
        TreeItem<OidTree.Node> current = tree.getRoot();
        while (current.getValue() == null || !current.getValue().oid().equals(oid)) {
            Optional<TreeItem<OidTree.Node>> next = current.getChildren().stream()
                    .filter(c -> oid.startsWith(c.getValue().oid()))
                    .findFirst();
            if (next.isEmpty()) {
                break;
            }
            current.setExpanded(true);
            current = next.get();
        }
        if (current.getValue() == null) {
            return;
        }
        if (select) {
            tree.getSelectionModel().select(current);
            int row = tree.getRow(current);
            tree.scrollTo(Math.max(0, row - 3));
        } else {
            current.setExpanded(true);
        }
    }
}
