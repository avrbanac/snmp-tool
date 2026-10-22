package hr.avrbanac.snmptool.core.mib.parse;

import hr.avrbanac.snmptool.core.mib.model.SourcePosition;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Abstract syntax tree of a MIB module, as written in the source. Produced by the {@link Parser} and
 * consumed by the resolver. Names are not resolved at this stage.
 */
public final class Ast {

    private Ast() {
    }

    /**
     * One {@code DEFINITIONS ::= BEGIN ... END} module. The offsets delimit the module in the source text,
     * from the module name to just after {@code END}.
     */
    public record Module(
            String name,
            SourcePosition position,
            List<Import> imports,
            List<Definition> definitions,
            int startOffset,
            int endOffset) {
        public Module {
            imports = List.copyOf(imports);
            definitions = List.copyOf(definitions);
        }
    }

    /** {@code symbols FROM module}. */
    public record Import(String module, List<String> symbols, SourcePosition position) {
        public Import {
            symbols = List.copyOf(symbols);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Definitions

    /** Any top-level assignment in a module. */
    public sealed interface Definition {
        String name();

        SourcePosition position();
    }

    /** A definition whose value is an object identifier. */
    public sealed interface OidDefinition extends Definition {
        OidValue oid();
    }

    /** {@code name MACRO ::= BEGIN ... END}; the body is skipped. */
    public record Macro(String name, SourcePosition position) implements Definition {
    }

    /** {@code name OBJECT IDENTIFIER ::= { ... }}. */
    public record ValueAssignment(String name, SourcePosition position, OidValue oid) implements OidDefinition {
    }

    /** {@code MODULE-IDENTITY}. */
    public record ModuleIdentity(
            String name,
            SourcePosition position,
            Optional<String> lastUpdated,
            Optional<String> organization,
            Optional<String> contactInfo,
            Optional<String> description,
            List<Revision> revisions,
            OidValue oid) implements OidDefinition {
        public ModuleIdentity {
            revisions = List.copyOf(revisions);
        }
    }

    /** {@code REVISION "date" DESCRIPTION "text"} inside {@code MODULE-IDENTITY}. */
    public record Revision(String date, Optional<String> description) {
    }

    /** {@code OBJECT-IDENTITY}. */
    public record ObjectIdentity(
            String name,
            SourcePosition position,
            Optional<String> status,
            Optional<String> description,
            Optional<String> reference,
            OidValue oid) implements OidDefinition {
    }

    /** {@code OBJECT-TYPE} (SMIv1 and SMIv2). */
    public record ObjectType(
            String name,
            SourcePosition position,
            Optional<Type> syntax,
            Optional<String> units,
            Optional<String> access,
            Optional<String> status,
            Optional<String> description,
            Optional<String> reference,
            List<IndexItem> index,
            Optional<String> augments,
            Optional<String> defval,
            OidValue oid) implements OidDefinition {
        public ObjectType {
            index = List.copyOf(index);
        }
    }

    /** One element of an {@code INDEX} clause. */
    public record IndexItem(String name, boolean implied) {
    }

    /** {@code NOTIFICATION-TYPE}. */
    public record NotificationType(
            String name,
            SourcePosition position,
            List<String> objects,
            Optional<String> status,
            Optional<String> description,
            Optional<String> reference,
            OidValue oid) implements OidDefinition {
        public NotificationType {
            objects = List.copyOf(objects);
        }
    }

    /** SMIv1 {@code TRAP-TYPE}. It has no OID of its own; the value is the specific trap number. */
    public record TrapType(
            String name,
            SourcePosition position,
            Optional<String> enterprise,
            List<String> variables,
            Optional<String> description,
            Optional<String> reference,
            long trapNumber) implements Definition {
        public TrapType {
            variables = List.copyOf(variables);
        }
    }

    /** Kinds of conformance definitions. */
    public enum ConformanceKind {
        OBJECT_GROUP,
        NOTIFICATION_GROUP,
        MODULE_COMPLIANCE,
        AGENT_CAPABILITIES
    }

    /**
     * {@code OBJECT-GROUP}, {@code NOTIFICATION-GROUP}, {@code MODULE-COMPLIANCE} or
     * {@code AGENT-CAPABILITIES}. Members are the objects or notifications of a group; the module sections of
     * compliance and capability statements are not kept.
     */
    public record Conformance(
            String name,
            SourcePosition position,
            ConformanceKind kind,
            List<String> members,
            Optional<String> status,
            Optional<String> description,
            Optional<String> reference,
            OidValue oid) implements OidDefinition {
        public Conformance {
            members = List.copyOf(members);
        }
    }

    /** {@code Name ::= TEXTUAL-CONVENTION ...}. */
    public record TextualConvention(
            String name,
            SourcePosition position,
            Optional<String> displayHint,
            Optional<String> status,
            Optional<String> description,
            Optional<String> reference,
            Optional<Type> syntax) implements Definition {
    }

    /** {@code Name ::= Type}. */
    public record TypeAssignment(String name, SourcePosition position, Type type) implements Definition {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Object identifier values

    /** {@code { component ... }}. */
    public record OidValue(List<OidComponent> components, SourcePosition position) {
        public OidValue {
            components = List.copyOf(components);
        }
    }

    /** One component of an OID value: {@code name}, {@code 42} or {@code name(42)}. */
    public record OidComponent(Optional<String> name, Optional<Long> number) {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Types

    /** A type as written in a SYNTAX clause or type assignment. */
    public sealed interface Type {
        SourcePosition position();
    }

    /** Built-in ASN.1 types used by SMI. */
    public enum BuiltinKind {
        INTEGER("INTEGER"),
        OCTET_STRING("OCTET STRING"),
        OBJECT_IDENTIFIER("OBJECT IDENTIFIER"),
        BITS("BITS"),
        NULL("NULL");

        private final String text;

        BuiltinKind(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /** A built-in type. */
    public record Builtin(BuiltinKind kind, SourcePosition position) implements Type {
    }

    /** Reference to a named type, optionally qualified with a module name. */
    public record TypeRef(Optional<String> module, String name, SourcePosition position) implements Type {
    }

    /** {@code [APPLICATION n] IMPLICIT Type} and similar. */
    public record Tagged(String tagClass, long number, boolean implicit, Type type, SourcePosition position)
            implements Type {
    }

    /** {@code Type { name(1), ... }} for enumerated INTEGER and BITS. */
    public record Enumerated(Type base, List<NamedNumber> values, SourcePosition position) implements Type {
        public Enumerated {
            values = List.copyOf(values);
        }
    }

    /** {@code Type (SIZE (...))} or {@code Type (...)}. */
    public record Constrained(Type base, boolean size, List<Range> ranges, SourcePosition position) implements Type {
        public Constrained {
            ranges = List.copyOf(ranges);
        }
    }

    /** {@code SEQUENCE { name Type, ... }}. */
    public record Sequence(List<Element> elements, SourcePosition position) implements Type {
        public Sequence {
            elements = List.copyOf(elements);
        }
    }

    /** {@code SEQUENCE OF Type}. */
    public record SequenceOf(Type element, SourcePosition position) implements Type {
    }

    /** {@code CHOICE { name Type, ... }}. */
    public record Choice(List<Element> elements, SourcePosition position) implements Type {
        public Choice {
            elements = List.copyOf(elements);
        }
    }

    /** Named element of a SEQUENCE or CHOICE. */
    public record Element(String name, Type type) {
    }

    /** {@code name(number)} in an enumeration or BITS definition. */
    public record NamedNumber(String name, BigInteger number) {
    }

    /** One alternative of a constraint: a single value or {@code lower..upper}. */
    public record Range(Bound lower, Bound upper) {
    }

    /** Range bound: a number or {@code MIN} / {@code MAX}. */
    public sealed interface Bound {
    }

    /** Numeric bound (decimal, hex or binary in the source). */
    public record Value(BigInteger value) implements Bound {
        @Override
        public String toString() {
            return value.toString();
        }
    }

    /** {@code MIN}. */
    public record Min() implements Bound {
        @Override
        public String toString() {
            return "MIN";
        }
    }

    /** {@code MAX}. */
    public record Max() implements Bound {
        @Override
        public String toString() {
            return "MAX";
        }
    }
}
