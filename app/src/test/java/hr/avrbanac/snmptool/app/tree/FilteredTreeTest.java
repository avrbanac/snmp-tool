package hr.avrbanac.snmptool.app.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hr.avrbanac.snmptool.core.mib.BundledMibs;
import hr.avrbanac.snmptool.core.mib.MibLoader;
import hr.avrbanac.snmptool.core.mib.MibSearch;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.model.OidTree;
import java.util.List;
import java.util.Optional;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.Test;

class FilteredTreeTest {

    private static final MibModel MODEL = MibLoader.load(BundledMibs.ietf());
    private static final MibSearch SEARCH = new MibSearch(MODEL);

    private static TreeItem<OidTree.Node> build(String query) {
        return FilteredTree.build(SEARCH.search(query, false, 1000).nodes());
    }

    /** Depth-first search of the built items (does not load lazy children). */
    private static Optional<TreeItem<OidTree.Node>> find(TreeItem<OidTree.Node> item, String label) {
        if (item.getValue() != null && item.getValue().label().equals(label)) {
            return Optional.of(item);
        }
        if (item instanceof LazyTreeItem) {
            return Optional.empty();
        }
        return item.getChildren().stream().map(c -> find(c, label)).flatMap(Optional::stream).findFirst();
    }

    private static List<String> labels(List<TreeItem<OidTree.Node>> items) {
        return items.stream().map(i -> i.getValue().label()).toList();
    }

    @Test
    void matchWithoutDeeperMatchesIsCollapsedButExpandable() {
        TreeItem<OidTree.Node> root = build("mib-2");
        TreeItem<OidTree.Node> mib2 = find(root, "mib-2").orElseThrow();
        assertInstanceOf(LazyTreeItem.class, mib2);
        assertFalse(mib2.isExpanded());
        assertFalse(mib2.isLeaf());
        assertTrue(labels(mib2.getChildren()).containsAll(List.of("system", "interfaces", "ifMIB")));
    }

    @Test
    void nestedMatchesShowOnlyThePathToDeeperMatches() {
        TreeItem<OidTree.Node> root = build("ifStack");
        TreeItem<OidTree.Node> table = find(root, "ifStackTable").orElseThrow();
        assertTrue(table.isExpanded());
        assertEquals(List.of("ifStackEntry"), labels(table.getChildren()));
        TreeItem<OidTree.Node> entry = table.getChildren().getFirst();
        assertEquals(List.of("ifStackHigherLayer", "ifStackLowerLayer", "ifStackStatus"), labels(entry.getChildren()));
        assertInstanceOf(LazyTreeItem.class, find(root, "ifStackGroup2").orElseThrow());

        TreeItem<OidTree.Node> objects = find(root, "ifMIBObjects").orElseThrow();
        assertEquals(List.of("ifStackTable", "ifStackLastChange"), labels(objects.getChildren()));
    }

    @Test
    void showAllChildrenAddsSiblingsAndKeepsShownItems() {
        TreeItem<OidTree.Node> root = build("ifStack");
        TreeItem<OidTree.Node> objects = find(root, "ifMIBObjects").orElseThrow();
        TreeItem<OidTree.Node> table = objects.getChildren().getFirst();
        assertTrue(FilteredTree.hasHiddenChildren(objects));

        FilteredTree.showAllChildren(objects);

        assertEquals(objects.getValue().children().size(), objects.getChildren().size());
        assertTrue(labels(objects.getChildren()).containsAll(List.of("ifXTable", "ifStackTable", "ifTestTable")));
        assertTrue(objects.getChildren().contains(table), "existing item is kept");
        assertTrue(table.isExpanded());
        assertFalse(FilteredTree.hasHiddenChildren(objects));
        assertInstanceOf(LazyTreeItem.class, find(root, "ifXTable").orElseThrow());
    }

    @Test
    void lazyItemsNeverReportHiddenChildren() {
        TreeItem<OidTree.Node> mib2 = find(build("mib-2"), "mib-2").orElseThrow();
        assertFalse(FilteredTree.hasHiddenChildren(mib2));
    }
}
