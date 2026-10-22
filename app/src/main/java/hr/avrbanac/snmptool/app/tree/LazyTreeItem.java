package hr.avrbanac.snmptool.app.tree;

import hr.avrbanac.snmptool.core.mib.model.OidTree;
import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;

/**
 * Tree item that creates its children the first time they are needed.
 */
final class LazyTreeItem extends TreeItem<OidTree.Node> {

    private boolean loaded;

    LazyTreeItem(OidTree.Node node) {
        super(node);
    }

    @Override
    public boolean isLeaf() {
        return getValue().children().isEmpty();
    }

    @Override
    public ObservableList<TreeItem<OidTree.Node>> getChildren() {
        if (!loaded) {
            loaded = true;
            super.getChildren().setAll(getValue().children().stream().map(LazyTreeItem::new).toList());
        }
        return super.getChildren();
    }
}
