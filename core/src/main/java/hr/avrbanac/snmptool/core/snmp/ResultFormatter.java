package hr.avrbanac.snmptool.core.snmp;

import hr.avrbanac.snmptool.core.mib.model.BaseType;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.model.MibNode;
import hr.avrbanac.snmptool.core.mib.model.MibType;
import hr.avrbanac.snmptool.core.mib.model.NodeKind;
import hr.avrbanac.snmptool.core.mib.model.Oid;
import hr.avrbanac.snmptool.core.mib.model.OidTree;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Turns variable bindings into text using the MIB model: instance names with decoded indexes, enumeration
 * labels, BITS names, display hints, TimeTicks durations, units and symbolic OIDs. Thread-safe.
 */
public final class ResultFormatter {

    private static final HexFormat HEX = HexFormat.ofDelimiter(" ");

    private final MibModel model;

    public ResultFormatter(MibModel model) {
        this.model = model;
    }

    /**
     * A formatted variable binding.
     *
     * @param oid   instance OID
     * @param name  instance name, e.g. {@code ifDescr.3}; the numeric OID if no definition matches
     * @param node  the MIB definition the OID falls under
     * @param type  SMI name of the value's wire type
     * @param value value for display (labels, hints, units)
     * @param raw   value without MIB interpretation (number, hex octets, dotted OID)
     */
    public record Formatted(Oid oid, String name, Optional<MibNode> node, String type, String value, String raw) {
    }

    public Formatted format(VarBindResult vb) {
        Optional<MibNode> node = definition(vb.oid());
        Optional<MibType> syntax = node.flatMap(MibNode::syntax);
        Optional<String> units = node.flatMap(MibNode::units);
        SnmpValue v = vb.value();
        String raw = raw(v);
        String value = switch (v) {
            case SnmpValue.Integer32 i -> withUnits(integer(i.value(), syntax), units);
            case SnmpValue.Gauge32 g -> withUnits(integer(g.value(), syntax), units);
            case SnmpValue.Counter32 c -> withUnits(Long.toString(c.value()), units);
            case SnmpValue.Counter64 c -> withUnits(c.value().toString(), units);
            case SnmpValue.TimeTicks t -> timeTicks(t.value()) + " (" + t.value() + ")";
            case SnmpValue.OctetString s -> octets(s.bytes(), syntax);
            case SnmpValue.ObjectId o -> name(o.oid());
            case SnmpValue.IpAddress ip -> ip.toString();
            case SnmpValue.Opaque o -> HEX.formatHex(o.bytes());
            case SnmpValue.Null _ -> "NULL";
            case SnmpValue.NoSuchObject _, SnmpValue.NoSuchInstance _, SnmpValue.EndOfMibView _ -> v.typeName();
        };
        return new Formatted(vb.oid(), name(vb.oid()), node, v.typeName(), value, raw);
    }

    /**
     * Name of an instance or object OID: the deepest definition above it plus the remaining arcs, with table
     * indexes decoded per the row's INDEX clause, e.g. {@code ifDescr.3}, {@code ipAdEntAddr.10.0.0.1} or
     * {@code vacmGroupName.3."public"}. The numeric OID if no definition matches.
     */
    public String name(Oid oid) {
        Optional<OidTree.Node> found = model.tree().longestPrefix(oid);
        if (found.isEmpty() || found.get().oid().size() == 1 && found.get().definition().get().module().isEmpty()) {
            return oid.toString();
        }
        MibNode def = found.get().definition().orElseThrow();
        long[] arcs = oid.arcs();
        long[] suffix = Arrays.copyOfRange(arcs, def.oid().size(), arcs.length);
        if (suffix.length == 0) {
            return def.name();
        }
        if (def.kind() == NodeKind.COLUMN) {
            Optional<String> index = decodeIndex(def, suffix);
            if (index.isPresent()) {
                return def.name() + "." + index.get();
            }
        }
        return def.name() + "." + join(suffix, 0, suffix.length);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Names and indexes

    private Optional<MibNode> definition(Oid oid) {
        return model.tree().longestPrefix(oid).flatMap(OidTree.Node::definition);
    }

    private Optional<String> decodeIndex(MibNode column, long[] suffix) {
        Optional<MibNode> row = column.oid().parent().flatMap(model.tree()::find).flatMap(OidTree.Node::definition);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        List<MibNode.IndexItem> index = row.get().index();
        if (index.isEmpty() && row.get().augments().isPresent()) {
            index = lookup(row.get().module(), row.get().augments().get()).map(MibNode::index).orElse(List.of());
        }
        if (index.isEmpty()) {
            return Optional.empty();
        }
        List<String> parts = new ArrayList<>();
        int pos = 0;
        for (int i = 0; i < index.size(); i++) {
            MibNode.IndexItem item = index.get(i);
            boolean last = i == index.size() - 1;
            Optional<MibType> type = lookup(row.get().module(), item.name()).flatMap(MibNode::syntax);
            BaseType base = type.map(MibType::baseType).orElseGet(() -> smiV1IndexType(item.name()));
            int remaining = suffix.length - pos;
            int consumed;
            String part;
            switch (base) {
                case INTEGER, INTEGER32, UNSIGNED32, GAUGE32, COUNTER32, COUNTER64, TIMETICKS -> {
                    if (remaining < 1) {
                        return Optional.empty();
                    }
                    consumed = 1;
                    part = Long.toString(suffix[pos]);
                }
                case IP_ADDRESS, NETWORK_ADDRESS -> {
                    if (remaining < 4) {
                        return Optional.empty();
                    }
                    consumed = 4;
                    part = join(suffix, pos, pos + 4);
                }
                case OCTET_STRING, BITS, OPAQUE -> {
                    int start = pos;
                    int length;
                    Optional<Integer> fixed = type.flatMap(ResultFormatter::fixedSize);
                    if (item.implied() && last) {
                        length = remaining;
                    } else if (fixed.isPresent()) {
                        length = fixed.get();
                    } else {
                        if (remaining < 1) {
                            return Optional.empty();
                        }
                        length = (int) suffix[pos];
                        start = pos + 1;
                    }
                    if (start + length > suffix.length || !allOctets(suffix, start, start + length)) {
                        return Optional.empty();
                    }
                    consumed = start + length - pos;
                    part = octetsIndex(suffix, start, start + length);
                }
                case OBJECT_IDENTIFIER -> {
                    int start = pos;
                    int length;
                    if (item.implied() && last) {
                        length = remaining;
                    } else {
                        if (remaining < 1) {
                            return Optional.empty();
                        }
                        length = (int) suffix[pos];
                        start = pos + 1;
                    }
                    if (start + length > suffix.length) {
                        return Optional.empty();
                    }
                    consumed = start + length - pos;
                    part = join(suffix, start, start + length);
                }
                default -> {
                    return Optional.empty();
                }
            }
            parts.add(part);
            pos += consumed;
        }
        if (pos < suffix.length) {
            parts.add(join(suffix, pos, suffix.length));
        }
        return Optional.of(String.join(".", parts));
    }

    private Optional<MibNode> lookup(String module, String name) {
        return model.find(module, name).or(() -> model.findByName(name).stream().findFirst());
    }

    /** SMIv1 allowed type names in INDEX clauses. */
    private static BaseType smiV1IndexType(String name) {
        return switch (name) {
            case "INTEGER" -> BaseType.INTEGER;
            case "OCTET STRING", "PhysAddress", "DisplayString" -> BaseType.OCTET_STRING;
            case "OBJECT IDENTIFIER" -> BaseType.OBJECT_IDENTIFIER;
            case "NetworkAddress", "IpAddress" -> BaseType.IP_ADDRESS;
            default -> BaseType.UNKNOWN;
        };
    }

    private static Optional<Integer> fixedSize(MibType type) {
        List<MibType.ValueRange> sizes = type.sizes();
        if (sizes.size() == 1 && sizes.getFirst().min().equals(sizes.getFirst().max())) {
            return Optional.of(sizes.getFirst().min().intValue());
        }
        return Optional.empty();
    }

    private static boolean allOctets(long[] arcs, int from, int to) {
        for (int i = from; i < to; i++) {
            if (arcs[i] > 255) {
                return false;
            }
        }
        return true;
    }

    /** Printable index strings in quotes, others as hex. */
    private static String octetsIndex(long[] arcs, int from, int to) {
        byte[] bytes = new byte[to - from];
        boolean printable = bytes.length > 0;
        for (int i = from; i < to; i++) {
            bytes[i - from] = (byte) arcs[i];
            printable &= arcs[i] >= 32 && arcs[i] < 127;
        }
        return printable ? "\"" + new String(bytes, StandardCharsets.US_ASCII) + "\"" : HexFormat.ofDelimiter(":").formatHex(bytes);
    }

    private static String join(long[] arcs, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) {
                sb.append('.');
            }
            sb.append(arcs[i]);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Values

    private static String integer(long value, Optional<MibType> syntax) {
        if (syntax.isPresent()) {
            MibType t = syntax.get();
            if (!t.namedValues().isEmpty() && t.baseType() != BaseType.BITS) {
                Optional<String> label = t.label(BigInteger.valueOf(value));
                return label.map(l -> l + "(" + value + ")").orElse(value + " (no label)");
            }
            Optional<String> hinted = t.displayHint().flatMap(h -> DisplayHint.formatInteger(h, value));
            if (hinted.isPresent()) {
                return hinted.get();
            }
        }
        return Long.toString(value);
    }

    private static String withUnits(String value, Optional<String> units) {
        return units.map(u -> value + " " + u).orElse(value);
    }

    private static String octets(byte[] bytes, Optional<MibType> syntax) {
        if (syntax.isPresent()) {
            MibType t = syntax.get();
            if (t.baseType() == BaseType.BITS) {
                return bits(bytes, t);
            }
            Optional<String> hinted = t.displayHint().flatMap(h -> DisplayHint.formatOctets(h, bytes));
            if (hinted.isPresent()) {
                return hinted.get();
            }
        }
        return text(bytes).orElseGet(() -> HEX.formatHex(bytes));
    }

    private static String bits(byte[] bytes, MibType type) {
        List<String> set = new ArrayList<>();
        for (int bit = 0; bit < bytes.length * 8; bit++) {
            if ((bytes[bit / 8] & (0x80 >> (bit % 8))) != 0) {
                int n = bit;
                set.add(type.label(BigInteger.valueOf(bit)).map(l -> l + "(" + n + ")").orElse(Integer.toString(bit)));
            }
        }
        return set.isEmpty() ? "{ }" : "{ " + String.join(", ", set) + " }";
    }

    /** The octets as text if they are valid UTF-8 without control characters (other than line breaks and tabs). */
    static Optional<String> text(byte[] bytes) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            boolean clean = s.chars().allMatch(c -> c >= 32 && c != 127 || c == '\n' || c == '\r' || c == '\t');
            return clean ? Optional.of(s) : Optional.empty();
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    /** {@code 1 day, 02:03:04.56}. */
    static String timeTicks(long ticks) {
        long days = ticks / 8_640_000;
        long rest = ticks % 8_640_000;
        String time = String.format("%02d:%02d:%02d.%02d", rest / 360_000, rest / 6_000 % 60, rest / 100 % 60, rest % 100);
        return days == 0 ? time : days + (days == 1 ? " day, " : " days, ") + time;
    }

    private static String raw(SnmpValue v) {
        return switch (v) {
            case SnmpValue.Integer32 i -> Integer.toString(i.value());
            case SnmpValue.Gauge32 g -> Long.toString(g.value());
            case SnmpValue.Counter32 c -> Long.toString(c.value());
            case SnmpValue.Counter64 c -> c.value().toString();
            case SnmpValue.TimeTicks t -> Long.toString(t.value());
            case SnmpValue.OctetString s -> HEX.formatHex(s.bytes());
            case SnmpValue.ObjectId o -> o.oid().toString();
            case SnmpValue.IpAddress ip -> ip.toString();
            case SnmpValue.Opaque o -> HEX.formatHex(o.bytes());
            case SnmpValue.Null _, SnmpValue.NoSuchObject _, SnmpValue.NoSuchInstance _, SnmpValue.EndOfMibView _ -> "";
        };
    }
}
