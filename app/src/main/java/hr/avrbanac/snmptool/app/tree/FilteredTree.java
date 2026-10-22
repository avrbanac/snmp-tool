package hr.avrbanac.snmptool.app.tree;

import hr.avrbanac.snmptool.core.mib.model.Oid;
import hr.avrbanac.snmptool.core.mib.model.OidTree;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javafx.scene.control.TreeItem;

/**
 * Builds the tree shown while searching: the matches and their ancestors.
 *
 * <p>A match with no deeper matches is a {@link LazyTreeItem}: collapsed, with its full subtree available on
 * expansion. Ancestors of matches (including matches with deeper matches) are expanded and show only the
 * path down to those matches; {@link #showAllChildren} adds the rest on request.
 */
final class FilteredTree {

    private FilteredTree() {
    }

    /**
     * Builds a hidden root holding the given matches, which must be in OID order.
     */
    static TreeItem<OidTree.Node> build(List<OidTree.Node> matches) {
        Set<Oid> ancestorsOfMatches = new HashSet<>();
        for (OidTree.Node match : matches) {
            for (Optional<OidTree.Node> p = match.parent(); p.isPresent(); p = p.get().parent()) {
                if (!ancestorsOfMatches.add(p.get().oid())) {
                    break; // the rest of the path is already recorded
                }
            }
        }

        TreeItem<OidTree.Node> hiddenRoot = new TreeItem<>();
        hiddenRoot.setExpanded(true);
        Map<Oid, TreeItem<OidTree.Node>> items = new HashMap<>();
        Function<OidTree.Node, TreeItem<OidTree.Node>> itemFor = new Function<>() {
            @Override
            public TreeItem<OidTree.Node> apply(OidTree.Node node) {
                TreeItem<OidTree.Node> existing = items.get(node.oid());
                if (existing != null) {
                    return existing;
                }
                TreeItem<OidTree.Node> parent = node.parent().map(this).orElse(hiddenRoot);
                TreeItem<OidTree.Node> item;
                if (ancestorsOfMatches.contains(node.oid())) {
                    item = new TreeItem<>(node);
                    item.setExpanded(true);
                } else {
                    item = new LazyTreeItem(node);
                }
                parent.getChildren().add(item);
                items.put(node.oid(), item);
                return item;
            }
        };
        matches.forEach(itemFor::apply);
        return hiddenRoot;
    }

    /** Whether the item shows only some of its node's children. */
    static boolean hasHiddenChildren(TreeItem<OidTree.Node> item) {
        return item.getValue() != null
                && !(item instanceof LazyTreeItem)
                && item.getChildren().size() < item.getValue().children().size();
    }

    /**
     * Adds the node's missing children, in OID order. Items already shown are kept as they are, so deeper
     * matches stay expanded; the added ones are collapsed and lazy.
     */
    static void showAllChildren(TreeItem<OidTree.Node> item) {
        if (!hasHiddenChildren(item)) {
            return;
        }
        Map<Oid, TreeItem<OidTree.Node>> shown = new HashMap<>();
        item.getChildren().forEach(c -> shown.put(c.getValue().oid(), c));
        List<TreeItem<OidTree.Node>> all = item.getValue().children().stream()
                .map(c -> shown.containsKey(c.oid()) ? shown.get(c.oid()) : (TreeItem<OidTree.Node>) new LazyTreeItem(c))
                .toList();
        item.getChildren().setAll(all);
        item.setExpanded(true);
    }
}
