package hr.avrbanac.snmptool.core.mib.model;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A resolved type: a named type (textual convention or type assignment) or an anonymous refinement written
 * in a SYNTAX clause, such as {@code DisplayString (SIZE (0..32))}.
 *
 * <p>Named values, sizes, ranges and the display hint are the <em>effective</em> ones, inherited from the
 * parent type when not refined.
 *
 * @param name              type name; empty for an anonymous type
 * @param module            defining module; empty for anonymous and built-in types
 * @param baseType          underlying SMI type
 * @param parent            the named type this one is derived from, if any
 * @param namedValues       enumeration labels (INTEGER) or bit names (BITS)
 * @param sizes             permitted sizes (OCTET STRING); empty if unrestricted
 * @param ranges            permitted values (numeric types); empty if unrestricted
 * @param displayHint       DISPLAY-HINT of the type or the nearest textual convention it derives from
 * @param elements          members of a SEQUENCE or CHOICE
 * @param elementType       element type of a SEQUENCE OF
 * @param textualConvention details when this type is a textual convention
 * @param position          where the type is defined; empty for built-in types
 */
public record MibType(
        String name,
        String module,
        BaseType baseType,
        Optional<MibType> parent,
        List<NamedValue> namedValues,
        List<ValueRange> sizes,
        List<ValueRange> ranges,
        Optional<String> displayHint,
        List<Element> elements,
        Optional<MibType> elementType,
        Optional<TextualConvention> textualConvention,
        Optional<SourcePosition> position) {

    public MibType {
        namedValues = List.copyOf(namedValues);
        sizes = List.copyOf(sizes);
        ranges = List.copyOf(ranges);
        elements = List.copyOf(elements);
    }

    /** Anonymous type with only a base type. */
    public static MibType of(BaseType baseType) {
        return new MibType("", "", baseType, Optional.empty(), List.of(), List.of(), List.of(), Optional.empty(),
                List.of(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** {@code name(number)} in an enumeration or BITS definition. */
    public record NamedValue(String name, BigInteger number) {
        @Override
        public String toString() {
            return name + "(" + number + ")";
        }
    }

    /** Inclusive range of permitted values or sizes. */
    public record ValueRange(BigInteger min, BigInteger max) {
        @Override
        public String toString() {
            return min.equals(max) ? min.toString() : min + ".." + max;
        }
    }

    /** Member of a SEQUENCE or CHOICE. */
    public record Element(String name, MibType type) {
    }

    /** Clauses of a TEXTUAL-CONVENTION. */
    public record TextualConvention(Optional<String> status, Optional<String> description, Optional<String> reference) {
    }

    /** Whether this type has a name of its own. */
    public boolean isNamed() {
        return !name.isEmpty();
    }

    /** Label of a named value, if the number is one of the enumerations or bits. */
    public Optional<String> label(BigInteger number) {
        return namedValues.stream().filter(v -> v.number().equals(number)).map(NamedValue::name).findFirst();
    }

    /**
     * The type as it would be written in a SYNTAX clause, e.g. {@code DisplayString (SIZE (0..32))} or
     * {@code INTEGER { up(1), down(2) }}.
     */
    public String syntaxText() {
        if (isNamed()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(parent.map(MibType::name).orElse(baseType.text()));
        if (baseType == BaseType.SEQUENCE_OF) {
            elementType.ifPresent(e -> sb.append(' ').append(e.syntaxText()));
        }
        Optional<MibType> p = parent;
        if (!namedValues.isEmpty() && !p.map(MibType::namedValues).orElse(List.of()).equals(namedValues)) {
            sb.append(namedValues.stream().map(NamedValue::toString).collect(Collectors.joining(", ", " { ", " }")));
        }
        if (!sizes.isEmpty() && !p.map(MibType::sizes).orElse(List.of()).equals(sizes)) {
            sb.append(" (SIZE (").append(rangesText(sizes)).append("))");
        }
        if (!ranges.isEmpty() && !p.map(MibType::ranges).orElse(List.of()).equals(ranges)) {
            sb.append(" (").append(rangesText(ranges)).append(')');
        }
        return sb.toString();
    }

    private static String rangesText(List<ValueRange> ranges) {
        return ranges.stream().map(ValueRange::toString).collect(Collectors.joining(" | "));
    }

    @Override
    public String toString() {
        return syntaxText();
    }
}
