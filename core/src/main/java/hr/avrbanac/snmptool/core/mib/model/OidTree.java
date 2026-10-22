package hr.avrbanac.snmptool.core.mib.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * All MIB nodes arranged by object identifier. Arcs that no loaded module names still get a tree node, so the
 * tree is always connected from the top-level arcs down. Immutable once built.
 */
public final class OidTree {

    private final List<Node> roots;
    private final Map<Oid, Node> byOid;

    private OidTree(List<Node> roots, Map<Oid, Node> byOid) {
        this.roots = roots;
        this.byOid = byOid;
    }

    /**
     * Builds a tree. When several definitions share an OID, all are kept and the first one in the given order
     * is the node's primary definition.
     */
    public static OidTree build(Collection<MibNode> nodes) {
        Map<Oid, Node> byOid = new HashMap<>();
        TreeMap<Long, Node> roots = new TreeMap<>();
        for (MibNode definition : nodes) {
            Node node = getOrCreate(definition.oid(), byOid, roots);
            node.definitions.add(definition);
        }
        byOid.values().forEach(Node::freeze);
        return new OidTree(List.copyOf(roots.values()), Map.copyOf(byOid));
    }

    private static Node getOrCreate(Oid oid, Map<Oid, Node> byOid, TreeMap<Long, Node> roots) {
        Node existing = byOid.get(oid);
        if (existing != null) {
            return existing;
        }
        Optional<Oid> parentOid = oid.parent();
        Node parent = parentOid.map(p -> getOrCreate(p, byOid, roots)).orElse(null);
        Node node = new Node(oid, parent);
        byOid.put(oid, node);
        if (parent == null) {
            roots.put(oid.lastArc(), node);
        } else {
            parent.childMap.put(oid.lastArc(), node);
        }
        return node;
    }

    /** Top-level nodes (normally {@code ccitt}, {@code iso} and {@code joint-iso-ccitt}), ordered by arc. */
    public List<Node> roots() {
        return roots;
    }

    /** Node with exactly this OID. */
    public Optional<Node> find(Oid oid) {
        return Optional.ofNullable(byOid.get(oid));
    }

    /**
     * The deepest node with a definition whose OID is a prefix of (or equal to) the given OID. Used to name
     * instance OIDs, e.g. {@code 1.3.6.1.2.1.2.2.1.2.3} gives {@code ifDescr}.
     */
    public Optional<Node> longestPrefix(Oid oid) {
        Optional<Oid> current = Optional.of(oid);
        while (current.isPresent()) {
            Node node = byOid.get(current.get());
            if (node != null && node.definition().isPresent()) {
                return Optional.of(node);
            }
            current = current.get().parent();
        }
        return Optional.empty();
    }

    /** All nodes in OID order (depth-first, parents before children). */
    public Stream<Node> stream() {
        return roots.stream().flatMap(Node::subtree);
    }

    /** Number of nodes, including unnamed intermediate nodes. */
    public int size() {
        return byOid.size();
    }

    /** A position in the tree. */
    public static final class Node {
        private final Oid oid;
        private final Node parent;
        private final TreeMap<Long, Node> childMap = new TreeMap<>();
        private List<MibNode> definitions = new ArrayList<>();
        private List<Node> children = List.of();

        private Node(Oid oid, Node parent) {
            this.oid = oid;
            this.parent = parent;
        }

        private void freeze() {
            definitions = List.copyOf(definitions);
            children = Collections.unmodifiableList(new ArrayList<>(childMap.values()));
        }

        public Oid oid() {
            return oid;
        }

        public Optional<Node> parent() {
            return Optional.ofNullable(parent);
        }

        /** Child nodes ordered by arc. */
        public List<Node> children() {
            return children;
        }

        /** All definitions with this OID, primary first. Empty for unnamed intermediate nodes. */
        public List<MibNode> definitions() {
            return definitions;
        }

        /** The primary definition. */
        public Optional<MibNode> definition() {
            return definitions.stream().findFirst();
        }

        /** This node and all its descendants in OID order. */
        public Stream<Node> subtree() {
            return Stream.concat(Stream.of(this), children.stream().flatMap(Node::subtree));
        }

        /** Labels from the top of the tree down to this node, e.g. {@code iso.org.dod.internet}. */
        public String symbolicPath() {
            return parent == null ? label() : parent.symbolicPath() + "." + label();
        }

        /** Name of the primary definition, or the arc number for unnamed nodes. */
        public String label() {
            return definition().map(MibNode::name).orElse(Long.toString(oid.lastArc()));
        }

        @Override
        public String toString() {
            return label() + " (" + oid + ")";
        }
    }
}
