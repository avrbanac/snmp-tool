package hr.avrbanac.snmptool.app;

import hr.avrbanac.snmptool.core.mib.model.NodeKind;
import java.util.Locale;

/**
 * Display names and CSS style classes for {@link NodeKind}s.
 */
public final class NodeKinds {

    private NodeKinds() {
    }

    public static String label(NodeKind kind) {
        return switch (kind) {
            case OBJECT_IDENTIFIER -> "Object identifier";
            case MODULE_IDENTITY -> "Module identity";
            case OBJECT_IDENTITY -> "Object identity";
            case SCALAR -> "Scalar";
            case TABLE -> "Table";
            case ROW -> "Table row";
            case COLUMN -> "Table column";
            case NOTIFICATION -> "Notification";
            case TRAP -> "Trap (SMIv1)";
            case OBJECT_GROUP -> "Object group";
            case NOTIFICATION_GROUP -> "Notification group";
            case MODULE_COMPLIANCE -> "Module compliance";
            case AGENT_CAPABILITIES -> "Agent capabilities";
        };
    }

    /** Style class such as {@code kind-scalar}; colours are defined in {@code app.css}. */
    public static String styleClass(NodeKind kind) {
        return "kind-" + kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
