package hr.avrbanac.snmptool.core.mib.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The result of loading a set of MIB modules: the resolved modules, the merged OID tree and all diagnostics.
 * Immutable.
 */
public final class MibModel {

    private final List<MibModule> modules;
    private final Map<String, MibModule> modulesByName;
    private final Map<String, List<MibNode>> nodesByName;
    private final OidTree tree;
    private final List<Diagnostic> diagnostics;

    public MibModel(List<MibModule> modules, OidTree tree, List<Diagnostic> diagnostics) {
        this.modules = List.copyOf(modules);
        this.tree = tree;
        this.diagnostics = List.copyOf(diagnostics);
        Map<String, MibModule> byName = new HashMap<>();
        Map<String, List<MibNode>> nodes = new HashMap<>();
        for (MibModule module : this.modules) {
            byName.putIfAbsent(module.name(), module);
            for (MibNode node : module.nodes()) {
                nodes.computeIfAbsent(node.name(), _ -> new ArrayList<>()).add(node);
            }
        }
        this.modulesByName = Map.copyOf(byName);
        // Wrapped rather than copied: large MIB sets have hundreds of thousands of names.
        nodes.replaceAll((name, list) -> List.copyOf(list));
        this.nodesByName = Collections.unmodifiableMap(nodes);
    }

    /** All modules, in load order. */
    public List<MibModule> modules() {
        return modules;
    }

    public Optional<MibModule> module(String name) {
        return Optional.ofNullable(modulesByName.get(name));
    }

    public OidTree tree() {
        return tree;
    }

    /** All problems found while parsing and resolving. */
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    /** All definitions with this name, from any module. */
    public List<MibNode> findByName(String name) {
        return nodesByName.getOrDefault(name, List.of());
    }

    /** The definition with this name in the given module. */
    public Optional<MibNode> find(String module, String name) {
        return findByName(name).stream().filter(n -> n.module().equals(module)).findFirst();
    }

    /**
     * Finds a node by numeric OID ({@code 1.3.6.1.2.1.1}), qualified name ({@code SNMPv2-MIB::system}) or plain
     * name ({@code system}, first match).
     */
    public Optional<MibNode> lookup(String text) {
        String s = text.strip();
        int separator = s.indexOf("::");
        if (separator > 0) {
            return find(s.substring(0, separator), s.substring(separator + 2));
        }
        if (!s.isEmpty() && (Character.isDigit(s.charAt(0)) || s.charAt(0) == '.')) {
            try {
                return tree.find(Oid.parse(s)).flatMap(OidTree.Node::definition);
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return findByName(s).stream().findFirst();
    }
}
