package hr.avrbanac.snmptool.core.mib.model;

import java.util.List;
import java.util.Optional;

/**
 * A resolved definition that has an object identifier.
 *
 * @param name        descriptor, e.g. {@code ifDescr}
 * @param module      defining module; empty for the built-in roots
 * @param oid         numeric object identifier
 * @param kind        what the definition is
 * @param syntax      SYNTAX of an OBJECT-TYPE
 * @param access      MAX-ACCESS (SMIv2) or ACCESS (SMIv1)
 * @param status      STATUS
 * @param units       UNITS
 * @param description DESCRIPTION
 * @param reference   REFERENCE
 * @param index       INDEX of a row
 * @param augments    AUGMENTS of a row
 * @param defval      DEFVAL as written in the source, without braces
 * @param members     OBJECTS, NOTIFICATIONS or VARIABLES
 * @param position    where the definition is; empty for the built-in roots
 */
public record MibNode(
        String name,
        String module,
        Oid oid,
        NodeKind kind,
        Optional<MibType> syntax,
        Optional<String> access,
        Optional<String> status,
        Optional<String> units,
        Optional<String> description,
        Optional<String> reference,
        List<IndexItem> index,
        Optional<String> augments,
        Optional<String> defval,
        List<String> members,
        Optional<SourcePosition> position) {

    public MibNode {
        index = List.copyOf(index);
        members = List.copyOf(members);
    }

    /** One element of an INDEX clause. */
    public record IndexItem(String name, boolean implied) {
        @Override
        public String toString() {
            return implied ? "IMPLIED " + name : name;
        }
    }

    /** {@code MODULE::name}, or just the name for built-in roots. */
    public String qualifiedName() {
        return module.isEmpty() ? name : module + "::" + name;
    }

    /** Copy with a different kind. */
    public MibNode withKind(NodeKind newKind) {
        return new MibNode(name, module, oid, newKind, syntax, access, status, units, description, reference, index,
                augments, defval, members, position);
    }

    @Override
    public String toString() {
        return qualifiedName() + " (" + oid + ")";
    }
}
