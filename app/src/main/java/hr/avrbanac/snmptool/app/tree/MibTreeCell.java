package hr.avrbanac.snmptool.app.tree;

import hr.avrbanac.snmptool.app.NodeKinds;
import hr.avrbanac.snmptool.core.mib.model.MibNode;
import hr.avrbanac.snmptool.core.mib.model.Oid;
import hr.avrbanac.snmptool.core.mib.model.OidTree;
import java.util.Optional;
import java.util.function.Predicate;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

/**
 * Tree cell showing a kind icon, the node name and its arc number.
 */
final class MibTreeCell extends TreeCell<OidTree.Node> {

    private final Predicate<Oid> isMatch;
    private final Region icon = new Region();
    private final Label name = new Label();
    private final Label arc = new Label();
    private final HBox box = new HBox(5, icon, name, arc);
    private final Tooltip tooltip = new Tooltip();

    MibTreeCell(Predicate<Oid> isMatch) {
        this.isMatch = isMatch;
        box.setAlignment(Pos.CENTER_LEFT);
    }

    @Override
    protected void updateItem(OidTree.Node node, boolean empty) {
        super.updateItem(node, empty);
        setText(null);
        if (empty || node == null) {
            setGraphic(null);
            setTooltip(null);
            return;
        }
        Optional<MibNode> definition = node.definition();
        icon.getStyleClass().setAll("kind-icon",
                definition.map(d -> NodeKinds.styleClass(d.kind())).orElse("kind-none"));

        name.setText(node.label());
        name.getStyleClass().setAll("label", "tree-name");
        definition.flatMap(MibNode::status)
                .filter(s -> s.equals("deprecated") || s.equals("obsolete"))
                .ifPresent(s -> name.getStyleClass().add(s));
        if (isMatch.test(node.oid())) {
            name.getStyleClass().add("search-match");
        }

        arc.setText(definition.isPresent() ? "(" + node.oid().lastArc() + ")" : "");
        arc.getStyleClass().setAll("label", "tree-arc");

        tooltip.setText(definition.map(d -> NodeKinds.label(d.kind()) + " · " + d.qualifiedName()).orElse("Unnamed")
                + "\n" + node.oid());
        setTooltip(tooltip);
        setGraphic(box);
    }
}
