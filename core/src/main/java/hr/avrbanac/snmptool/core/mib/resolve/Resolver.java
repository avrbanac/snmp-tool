package hr.avrbanac.snmptool.core.mib.resolve;

import hr.avrbanac.snmptool.core.mib.model.BaseType;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.model.MibModule;
import hr.avrbanac.snmptool.core.mib.model.MibModule.SmiVersion;
import hr.avrbanac.snmptool.core.mib.model.MibNode;
import hr.avrbanac.snmptool.core.mib.model.MibType;
import hr.avrbanac.snmptool.core.mib.model.MibType.NamedValue;
import hr.avrbanac.snmptool.core.mib.model.MibType.ValueRange;
import hr.avrbanac.snmptool.core.mib.model.NodeKind;
import hr.avrbanac.snmptool.core.mib.model.Oid;
import hr.avrbanac.snmptool.core.mib.model.OidTree;
import hr.avrbanac.snmptool.core.mib.model.SourcePosition;
import hr.avrbanac.snmptool.core.mib.parse.Ast;
import hr.avrbanac.snmptool.core.mib.parse.Diagnostics;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves parsed modules into a {@link MibModel}: imports, object identifiers and types.
 *
 * <p>All modules are resolved together, so forward references and import cycles are handled naturally.
 * Problems are reported to {@link Diagnostics}; unresolvable definitions are left out of the tree or get the
 * {@link BaseType#UNKNOWN} type, and everything else still loads.
 */
public final class Resolver {

    /** Modules that define the SMI base types. */
    private static final Set<String> SMI_MODULES = Set.of("SNMPv2-SMI", "RFC1155-SMI");

    /** SMI application types, recognised by name when defined in an SMI module. */
    private static final Map<String, BaseType> SMI_TYPES = Map.ofEntries(
            Map.entry("Integer32", BaseType.INTEGER32),
            Map.entry("Unsigned32", BaseType.UNSIGNED32),
            Map.entry("Gauge32", BaseType.GAUGE32),
            Map.entry("Gauge", BaseType.GAUGE32),
            Map.entry("Counter32", BaseType.COUNTER32),
            Map.entry("Counter", BaseType.COUNTER32),
            Map.entry("Counter64", BaseType.COUNTER64),
            Map.entry("TimeTicks", BaseType.TIMETICKS),
            Map.entry("IpAddress", BaseType.IP_ADDRESS),
            Map.entry("Opaque", BaseType.OPAQUE),
            Map.entry("NetworkAddress", BaseType.NETWORK_ADDRESS));

    /** Top-level arcs that need no definition. */
    private static final Map<String, Long> ROOTS = Map.of(
            "ccitt", 0L, "itu-t", 0L, "iso", 1L, "joint-iso-ccitt", 2L, "joint-iso-itu-t", 2L);

    private final Diagnostics diagnostics;
    private final Map<String, ParsedModule> modules = new LinkedHashMap<>();
    private final Map<String, Map<String, Ast.Definition>> definitions = new HashMap<>();

    private final Map<String, Optional<Oid>> oidCache = new HashMap<>();
    private final Set<String> oidInProgress = new HashSet<>();
    private final Map<String, MibType> typeCache = new HashMap<>();
    private final Set<String> typeInProgress = new HashSet<>();
    private final Set<String> reported = new HashSet<>();

    /**
     * A parsed module and the source it came from.
     *
     * @param module parsed module
     * @param source source name
     */
    public record ParsedModule(Ast.Module module, String source) {
    }

    /** A definition found by name lookup. */
    private record Symbol(String module, Ast.Definition definition) {
    }

    private Resolver(List<ParsedModule> parsed, Diagnostics diagnostics) {
        this.diagnostics = diagnostics;
        for (ParsedModule p : parsed) {
            String name = p.module().name();
            if (modules.containsKey(name)) {
                diagnostics.warning(p.module().position(), "Module " + name + " is already loaded from "
                        + modules.get(name).source() + "; this copy is ignored");
                continue;
            }
            modules.put(name, p);
            Map<String, Ast.Definition> defs = new HashMap<>();
            for (Ast.Definition d : p.module().definitions()) {
                Ast.Definition previous = defs.putIfAbsent(d.name(), d);
                if (previous != null) {
                    diagnostics.warning(d.position(), "Duplicate definition of " + d.name() + " in module " + name
                            + " (first at line " + previous.position().line() + "); this one is ignored");
                }
            }
            definitions.put(name, defs);
        }
    }

    /**
     * Resolves the modules. Modules with the same name as an earlier one are ignored with a warning.
     */
    public static MibModel resolve(List<ParsedModule> parsed, Diagnostics diagnostics) {
        return new Resolver(parsed, diagnostics).run();
    }

    private MibModel run() {
        modules.values().forEach(this::checkImports);

        List<MibModule> result = new ArrayList<>();
        List<MibNode> allNodes = new ArrayList<>(rootNodes());
        for (ParsedModule p : modules.values()) {
            MibModule module = resolveModule(p);
            result.add(module);
            allNodes.addAll(module.nodes());
        }

        // Columns can only be told apart from scalars once all rows are known.
        Set<Oid> rows = new HashSet<>();
        allNodes.stream().filter(n -> n.kind() == NodeKind.ROW).forEach(n -> rows.add(n.oid()));
        List<MibModule> classified = result.stream().map(m -> classifyColumns(m, rows)).toList();
        // When SMIv1 and SMIv2 modules define the same OID (RFC1213-MIB and IF-MIB, for example), the SMIv2
        // definition becomes the primary one; otherwise load order decides.
        List<MibNode> treeNodes = new ArrayList<>(rootNodes());
        classified.stream().filter(m -> m.smiVersion() == SmiVersion.V2).forEach(m -> treeNodes.addAll(m.nodes()));
        classified.stream().filter(m -> m.smiVersion() == SmiVersion.V1).forEach(m -> treeNodes.addAll(m.nodes()));

        return new MibModel(classified, OidTree.build(treeNodes), diagnostics.all());
    }

    private static List<MibNode> rootNodes() {
        return List.of(root("ccitt", 0), root("iso", 1), root("joint-iso-ccitt", 2));
    }

    private static MibNode root(String name, long arc) {
        return new MibNode(name, "", Oid.of(arc), NodeKind.OBJECT_IDENTIFIER, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(),
                Optional.empty(), List.of(), Optional.empty());
    }

    private static MibModule classifyColumns(MibModule m, Set<Oid> rows) {
        List<MibNode> nodes = m.nodes().stream()
                .map(n -> n.kind() == NodeKind.SCALAR && n.oid().parent().filter(rows::contains).isPresent()
                        ? n.withKind(NodeKind.COLUMN) : n)
                .toList();
        return new MibModule(m.name(), m.source(), m.smiVersion(), m.lastUpdated(), m.organization(),
                m.contactInfo(), m.description(), m.revisions(), m.imports(), nodes, m.types());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Imports and name lookup

    private void checkImports(ParsedModule p) {
        String moduleName = p.module().name();
        for (Ast.Import imp : p.module().imports()) {
            if (!modules.containsKey(imp.module())) {
                if (reported.add("missing-module:" + moduleName + ":" + imp.module())) {
                    diagnostics.error(imp.position(), "Module " + moduleName + " imports from " + imp.module()
                            + ", which is not loaded");
                }
                continue;
            }
            for (String symbol : imp.symbols()) {
                if (findExported(imp.module(), symbol, new HashSet<>()).isEmpty()) {
                    diagnostics.warning(imp.position(), "Symbol " + symbol + " is not defined in module "
                            + imp.module());
                }
            }
        }
    }

    /**
     * Finds a symbol defined in a module. Following that module's own imports is not allowed by the SMI, but
     * vendor MIBs rely on it, so it is done as a fallback.
     */
    private Optional<Symbol> findExported(String module, String name, Set<String> visited) {
        if (!visited.add(module)) {
            return Optional.empty();
        }
        Map<String, Ast.Definition> defs = definitions.get(module);
        if (defs == null) {
            return Optional.empty();
        }
        Ast.Definition d = defs.get(name);
        if (d != null) {
            return Optional.of(new Symbol(module, d));
        }
        for (Ast.Import imp : modules.get(module).module().imports()) {
            if (imp.symbols().contains(name)) {
                Optional<Symbol> s = findExported(imp.module(), name, visited);
                if (s.isPresent()) {
                    return s;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Looks up a name as seen from inside a module: own definitions, then imports, then (with a warning) any
     * other loaded module that defines it, preferring SMIv2 modules.
     */
    private Optional<Symbol> lookup(String module, String name, SourcePosition position) {
        Ast.Definition own = definitions.get(module).get(name);
        if (own != null) {
            return Optional.of(new Symbol(module, own));
        }
        for (Ast.Import imp : modules.get(module).module().imports()) {
            if (imp.symbols().contains(name)) {
                Optional<Symbol> s = findExported(imp.module(), name, new HashSet<>());
                if (s.isPresent()) {
                    return s;
                }
            }
        }
        Optional<Symbol> fallback = modules.keySet().stream()
                .sorted(Comparator.comparing((String m) -> !isSmiV2(m)))
                .filter(m -> definitions.get(m).get(name) != null && !(definitions.get(m).get(name) instanceof Ast.Macro))
                .findFirst()
                .map(m -> new Symbol(m, definitions.get(m).get(name)));
        if (fallback.isPresent() && reported.add("implicit:" + module + ":" + name)) {
            diagnostics.warning(position, name + " is not imported into " + module + "; using the definition from "
                    + fallback.get().module());
        }
        return fallback;
    }

    private boolean isSmiV2(String module) {
        Ast.Module ast = modules.get(module).module();
        return module.equals("SNMPv2-SMI")
                || ast.definitions().stream().anyMatch(d -> d instanceof Ast.ModuleIdentity)
                || ast.imports().stream().anyMatch(i -> i.module().equals("SNMPv2-SMI"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Modules and nodes

    private MibModule resolveModule(ParsedModule p) {
        Ast.Module ast = p.module();
        String name = ast.name();
        List<MibNode> nodes = new ArrayList<>();
        List<MibType> types = new ArrayList<>();
        Optional<Ast.ModuleIdentity> identity = Optional.empty();

        for (Ast.Definition d : ast.definitions()) {
            if (definitions.get(name).get(d.name()) != d) {
                continue; // duplicate, already reported
            }
            switch (d) {
                case Ast.ModuleIdentity mi -> {
                    identity = Optional.of(mi);
                    oid(name, mi).ifPresent(o -> nodes.add(node(name, mi, o, NodeKind.MODULE_IDENTITY)
                            .description(mi.description()).build()));
                }
                case Ast.ValueAssignment va -> oid(name, va).ifPresent(o -> nodes.add(
                        node(name, va, o, NodeKind.OBJECT_IDENTIFIER).build()));
                case Ast.ObjectIdentity oi -> oid(name, oi).ifPresent(o -> nodes.add(
                        node(name, oi, o, NodeKind.OBJECT_IDENTITY).status(oi.status()).description(oi.description())
                                .reference(oi.reference()).build()));
                case Ast.ObjectType ot -> oid(name, ot).ifPresent(o -> nodes.add(objectType(name, ot, o)));
                case Ast.NotificationType nt -> {
                    checkMembers(name, nt.objects(), nt.position());
                    oid(name, nt).ifPresent(o -> nodes.add(node(name, nt, o, NodeKind.NOTIFICATION)
                            .status(nt.status()).description(nt.description()).reference(nt.reference())
                            .members(nt.objects()).build()));
                }
                case Ast.Conformance c -> {
                    if (c.kind() == Ast.ConformanceKind.OBJECT_GROUP || c.kind() == Ast.ConformanceKind.NOTIFICATION_GROUP) {
                        checkMembers(name, c.members(), c.position());
                    }
                    oid(name, c).ifPresent(o -> nodes.add(node(name, c, o, conformanceKind(c.kind()))
                            .status(c.status()).description(c.description()).reference(c.reference())
                            .members(c.members()).build()));
                }
                case Ast.TrapType tt -> trap(name, tt).ifPresent(nodes::add);
                case Ast.TextualConvention tc -> types.add(namedType(name, tc.name(), tc.position()));
                case Ast.TypeAssignment ta -> types.add(namedType(name, ta.name(), ta.position()));
                case Ast.Macro _ -> {
                    // Macro semantics are built into the parser.
                }
            }
        }

        return new MibModule(name, p.source(), isSmiV2(name) ? SmiVersion.V2 : SmiVersion.V1,
                identity.flatMap(Ast.ModuleIdentity::lastUpdated),
                identity.flatMap(Ast.ModuleIdentity::organization),
                identity.flatMap(Ast.ModuleIdentity::contactInfo),
                identity.flatMap(Ast.ModuleIdentity::description),
                identity.map(i -> i.revisions().stream()
                        .map(r -> new MibModule.Revision(r.date(), r.description()))
                        .toList()).orElse(List.of()),
                ast.imports().stream().map(i -> new MibModule.Import(i.module(), i.symbols())).toList(),
                nodes, types);
    }

    private MibNode objectType(String module, Ast.ObjectType ot, Oid oid) {
        Optional<MibType> syntax = ot.syntax().map(t -> resolveType(module, t));
        NodeKind kind = NodeKind.SCALAR;
        BaseType base = syntax.map(MibType::baseType).orElse(BaseType.UNKNOWN);
        if (base == BaseType.SEQUENCE_OF) {
            kind = NodeKind.TABLE;
        } else if (base == BaseType.SEQUENCE || !ot.index().isEmpty() || ot.augments().isPresent()) {
            kind = NodeKind.ROW;
        }
        for (Ast.IndexItem item : ot.index()) {
            if (!item.name().contains(" ") && Character.isLowerCase(item.name().charAt(0))) {
                checkMembers(module, List.of(item.name()), ot.position());
            }
        }
        ot.augments().ifPresent(a -> checkMembers(module, List.of(a), ot.position()));
        return node(module, ot, oid, kind)
                .syntax(syntax).access(ot.access()).status(ot.status()).units(ot.units())
                .description(ot.description()).reference(ot.reference())
                .index(ot.index().stream().map(i -> new MibNode.IndexItem(i.name(), i.implied())).toList())
                .augments(ot.augments()).defval(ot.defval())
                .build();
    }

    /** SMIv1 traps are placed at {@code enterprise.0.trapNumber}, as in RFC 3584. */
    private Optional<MibNode> trap(String module, Ast.TrapType tt) {
        checkMembers(module, tt.variables(), tt.position());
        if (tt.enterprise().isEmpty()) {
            diagnostics.error(tt.position(), "TRAP-TYPE " + tt.name() + " has no ENTERPRISE");
            return Optional.empty();
        }
        Optional<Oid> enterprise = oidOfName(module, tt.enterprise().get(), tt.position());
        return enterprise.map(e -> node(module, tt, e.child(0).child(tt.trapNumber()), NodeKind.TRAP)
                .description(tt.description()).reference(tt.reference()).members(tt.variables()).build());
    }

    private void checkMembers(String module, List<String> names, SourcePosition position) {
        for (String member : names) {
            if (lookup(module, member, position).isEmpty() && reported.add("member:" + module + ":" + member)) {
                diagnostics.warning(position, "Unknown object " + member + " referenced in module " + module);
            }
        }
    }

    private static NodeKind conformanceKind(Ast.ConformanceKind kind) {
        return switch (kind) {
            case OBJECT_GROUP -> NodeKind.OBJECT_GROUP;
            case NOTIFICATION_GROUP -> NodeKind.NOTIFICATION_GROUP;
            case MODULE_COMPLIANCE -> NodeKind.MODULE_COMPLIANCE;
            case AGENT_CAPABILITIES -> NodeKind.AGENT_CAPABILITIES;
        };
    }

    private static NodeBuilder node(String module, Ast.Definition d, Oid oid, NodeKind kind) {
        return new NodeBuilder(d.name(), module, oid, kind, d.position());
    }

    /** Collects the optional parts of a {@link MibNode}. */
    private static final class NodeBuilder {
        private final String name;
        private final String module;
        private final Oid oid;
        private final NodeKind kind;
        private final SourcePosition position;
        private Optional<MibType> syntax = Optional.empty();
        private Optional<String> access = Optional.empty();
        private Optional<String> status = Optional.empty();
        private Optional<String> units = Optional.empty();
        private Optional<String> description = Optional.empty();
        private Optional<String> reference = Optional.empty();
        private List<MibNode.IndexItem> index = List.of();
        private Optional<String> augments = Optional.empty();
        private Optional<String> defval = Optional.empty();
        private List<String> members = List.of();

        NodeBuilder(String name, String module, Oid oid, NodeKind kind, SourcePosition position) {
            this.name = name;
            this.module = module;
            this.oid = oid;
            this.kind = kind;
            this.position = position;
        }

        NodeBuilder syntax(Optional<MibType> v) {
            syntax = v;
            return this;
        }

        NodeBuilder access(Optional<String> v) {
            access = v;
            return this;
        }

        NodeBuilder status(Optional<String> v) {
            status = v;
            return this;
        }

        NodeBuilder units(Optional<String> v) {
            units = v;
            return this;
        }

        NodeBuilder description(Optional<String> v) {
            description = v;
            return this;
        }

        NodeBuilder reference(Optional<String> v) {
            reference = v;
            return this;
        }

        NodeBuilder index(List<MibNode.IndexItem> v) {
            index = v;
            return this;
        }

        NodeBuilder augments(Optional<String> v) {
            augments = v;
            return this;
        }

        NodeBuilder defval(Optional<String> v) {
            defval = v;
            return this;
        }

        NodeBuilder members(List<String> v) {
            members = v;
            return this;
        }

        MibNode build() {
            return new MibNode(name, module, oid, kind, syntax, access, status, units, description, reference, index,
                    augments, defval, members, Optional.of(position));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Object identifiers

    private Optional<Oid> oid(String module, Ast.OidDefinition d) {
        String key = module + "::" + d.name();
        Optional<Oid> cached = oidCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (!oidInProgress.add(key)) {
            diagnostics.error(d.position(), "Circular object identifier definition of " + d.name());
            return Optional.empty();
        }
        Optional<Oid> result = computeOid(module, d.name(), d.oid());
        oidInProgress.remove(key);
        oidCache.put(key, result);
        return result;
    }

    private Optional<Oid> computeOid(String module, String name, Ast.OidValue value) {
        List<Ast.OidComponent> components = value.components();
        if (components.isEmpty()) {
            return Optional.empty();
        }
        Ast.OidComponent first = components.getFirst();
        Optional<Oid> oid;
        if (first.number().isPresent()) {
            oid = Optional.of(Oid.of(first.number().get()));
        } else {
            oid = oidOfName(module, first.name().orElseThrow(), value.position());
        }
        for (Ast.OidComponent c : components.subList(1, components.size())) {
            if (oid.isEmpty()) {
                return oid;
            }
            if (c.number().isEmpty()) {
                diagnostics.error(value.position(), "Object identifier of " + name + " uses '" + c.name().orElseThrow()
                        + "' without a number");
                return Optional.empty();
            }
            oid = Optional.of(oid.get().child(c.number().get()));
        }
        return oid;
    }

    private Optional<Oid> oidOfName(String module, String name, SourcePosition position) {
        Optional<Symbol> symbol = lookup(module, name, position);
        if (symbol.isEmpty()) {
            Long root = ROOTS.get(name);
            if (root != null) {
                return Optional.of(Oid.of(root));
            }
            diagnostics.error(position, "Unknown object identifier " + name + " in module " + module);
            return Optional.empty();
        }
        if (symbol.get().definition() instanceof Ast.OidDefinition d) {
            return oid(symbol.get().module(), d);
        }
        diagnostics.error(position, name + " is not an object identifier");
        return Optional.empty();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Types

    private MibType resolveType(String module, Ast.Type type) {
        return switch (type) {
            case Ast.Builtin b -> MibType.of(builtin(b.kind()));
            case Ast.TypeRef ref -> resolveRef(module, ref);
            case Ast.Tagged tagged -> resolveType(module, tagged.type());
            case Ast.Enumerated e -> {
                MibType base = resolveType(module, e.base());
                List<NamedValue> values = e.values().stream()
                        .map(v -> new NamedValue(v.name(), v.number())).toList();
                yield derive(base, values, base.sizes(), base.ranges());
            }
            case Ast.Constrained c -> {
                MibType base = resolveType(module, c.base());
                List<ValueRange> ranges = c.ranges().stream()
                        .map(r -> range(r, c.size() ? BaseType.OCTET_STRING : base.baseType())).toList();
                yield c.size()
                        ? derive(base, base.namedValues(), ranges, base.ranges())
                        : derive(base, base.namedValues(), base.sizes(), ranges);
            }
            case Ast.Sequence s -> structured(BaseType.SEQUENCE, module, s.elements());
            case Ast.Choice c -> structured(BaseType.CHOICE, module, c.elements());
            case Ast.SequenceOf s -> new MibType("", "", BaseType.SEQUENCE_OF, Optional.empty(), List.of(), List.of(),
                    List.of(), Optional.empty(), List.of(), Optional.of(resolveType(module, s.element())),
                    Optional.empty(), Optional.empty());
        };
    }

    private MibType structured(BaseType base, String module, List<Ast.Element> elements) {
        List<MibType.Element> resolved = elements.stream()
                .map(e -> new MibType.Element(e.name(), resolveType(module, e.type()))).toList();
        return new MibType("", "", base, Optional.empty(), List.of(), List.of(), List.of(), Optional.empty(),
                resolved, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private MibType resolveRef(String module, Ast.TypeRef ref) {
        Optional<Symbol> symbol = ref.module().isPresent()
                ? findExported(ref.module().get(), ref.name(), new HashSet<>())
                : lookup(module, ref.name(), ref.position());
        if (symbol.isPresent()) {
            Ast.Definition d = symbol.get().definition();
            if (d instanceof Ast.TypeAssignment || d instanceof Ast.TextualConvention) {
                return namedType(symbol.get().module(), d.name(), d.position());
            }
            diagnostics.error(ref.position(), ref.name() + " is not a type");
            return unknown(ref.name());
        }
        BaseType smi = SMI_TYPES.get(ref.name());
        if (smi != null) {
            if (reported.add("smi-type:" + module + ":" + ref.name())) {
                diagnostics.warning(ref.position(), "Type " + ref.name() + " is not imported into " + module
                        + "; using the SMI definition");
            }
            return named(MibType.of(smi), ref.name(), "", Optional.empty());
        }
        diagnostics.error(ref.position(), "Unknown type " + ref.name() + " in module " + module);
        return unknown(ref.name());
    }

    /** Resolves the type defined as {@code name} in {@code module}, caching the result. */
    private MibType namedType(String module, String name, SourcePosition position) {
        String key = module + "::" + name;
        MibType cached = typeCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (!typeInProgress.add(key)) {
            diagnostics.error(position, "Circular type definition of " + name);
            return unknown(name);
        }
        MibType result = switch (definitions.get(module).get(name)) {
            case Ast.TypeAssignment ta -> {
                MibType inner = resolveType(module, ta.type());
                if (SMI_MODULES.contains(module) && SMI_TYPES.containsKey(name)) {
                    inner = withBase(inner, SMI_TYPES.get(name));
                }
                yield named(inner, name, module, Optional.of(ta.position()));
            }
            case Ast.TextualConvention tc -> {
                MibType inner = tc.syntax().map(t -> resolveType(module, t)).orElse(MibType.of(BaseType.UNKNOWN));
                MibType n = named(inner, name, module, Optional.of(tc.position()));
                yield new MibType(n.name(), n.module(), n.baseType(), n.parent(), n.namedValues(), n.sizes(),
                        n.ranges(), tc.displayHint().or(n::displayHint), n.elements(), n.elementType(),
                        Optional.of(new MibType.TextualConvention(tc.status(), tc.description(), tc.reference())),
                        n.position());
            }
            default -> throw new IllegalStateException("Not a type: " + key);
        };
        typeInProgress.remove(key);
        typeCache.put(key, result);
        return result;
    }

    /**
     * Gives a type a name. An anonymous inner type is absorbed (its parent becomes the named type's parent); a
     * named inner type becomes the parent.
     */
    private static MibType named(MibType inner, String name, String module, Optional<SourcePosition> position) {
        Optional<MibType> parent = inner.isNamed() ? Optional.of(inner) : inner.parent();
        return new MibType(name, module, inner.baseType(), parent, inner.namedValues(), inner.sizes(), inner.ranges(),
                inner.displayHint(), inner.elements(), inner.elementType(), Optional.empty(), position);
    }

    /** Anonymous refinement of a type with the given effective values and constraints. */
    private static MibType derive(MibType base, List<NamedValue> values, List<ValueRange> sizes,
            List<ValueRange> ranges) {
        Optional<MibType> parent = base.isNamed() ? Optional.of(base) : base.parent();
        return new MibType("", "", base.baseType(), parent, values, sizes, ranges, base.displayHint(),
                base.elements(), base.elementType(), Optional.empty(), Optional.empty());
    }

    private static MibType withBase(MibType t, BaseType base) {
        return new MibType(t.name(), t.module(), base, t.parent(), t.namedValues(), t.sizes(), t.ranges(),
                t.displayHint(), t.elements(), t.elementType(), t.textualConvention(), t.position());
    }

    private static MibType unknown(String name) {
        return new MibType(name, "", BaseType.UNKNOWN, Optional.empty(), List.of(), List.of(), List.of(),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static BaseType builtin(Ast.BuiltinKind kind) {
        return switch (kind) {
            case INTEGER -> BaseType.INTEGER;
            case OCTET_STRING -> BaseType.OCTET_STRING;
            case OBJECT_IDENTIFIER -> BaseType.OBJECT_IDENTIFIER;
            case BITS -> BaseType.BITS;
            case NULL -> BaseType.NULL;
        };
    }

    private static ValueRange range(Ast.Range r, BaseType base) {
        return new ValueRange(bound(r.lower(), base), bound(r.upper(), base));
    }

    private static BigInteger bound(Ast.Bound b, BaseType base) {
        boolean size = base == BaseType.OCTET_STRING;
        return switch (b) {
            case Ast.Value v -> v.value();
            case Ast.Min _ -> size ? BigInteger.ZERO : base.min();
            case Ast.Max _ -> size ? BaseType.MAX_SIZE : base.max();
        };
    }
}
