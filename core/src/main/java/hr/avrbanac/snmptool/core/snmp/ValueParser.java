package hr.avrbanac.snmptool.core.snmp;

import hr.avrbanac.snmptool.core.mib.model.BaseType;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.model.MibNode;
import hr.avrbanac.snmptool.core.mib.model.MibType;
import hr.avrbanac.snmptool.core.mib.model.Oid;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Parses user input for a SET into an {@link SnmpValue}, checked against the MIB type: enumeration labels,
 * value ranges and sizes. Errors are reported as {@link IllegalArgumentException}s with a message meant for the
 * user.
 */
public final class ValueParser {

    private static final BigInteger MAX_UNSIGNED_32 = BigInteger.valueOf(0xFFFF_FFFFL);
    private static final BigInteger MAX_UNSIGNED_64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private ValueParser() {
    }

    /** Base types that can be written with SET. */
    public static final List<BaseType> WRITABLE_TYPES = List.of(BaseType.INTEGER, BaseType.UNSIGNED32,
            BaseType.GAUGE32, BaseType.COUNTER32, BaseType.COUNTER64, BaseType.TIMETICKS, BaseType.OCTET_STRING,
            BaseType.BITS, BaseType.OBJECT_IDENTIFIER, BaseType.IP_ADDRESS, BaseType.OPAQUE);

    /** Whether octet input for this type is hexadecimal by default (binary data or a hex display hint). */
    public static boolean prefersHex(MibType type) {
        return type.baseType() == BaseType.OPAQUE
                || type.displayHint().map(h -> h.contains("x") && !h.contains("a") && !h.contains("t")).orElse(false);
    }

    /**
     * Parses input for a value of the given base type.
     *
     * @param text  user input
     * @param base  base type to produce (the MIB type's, or the one chosen by the user)
     * @param type  MIB type used for labels and constraints, if known
     * @param hex   whether octet-string input is hexadecimal (e.g. {@code 0a 1b} or {@code 0x0a1b})
     * @param model used to resolve symbolic OIDs
     * @throws IllegalArgumentException if the input is not valid for the type
     */
    public static SnmpValue parse(String text, BaseType base, Optional<MibType> type, boolean hex, MibModel model) {
        String s = text.strip();
        return switch (base) {
            case INTEGER, INTEGER32 -> {
                BigInteger n = integer(s, type);
                checkRange(n, type, BigInteger.valueOf(Integer.MIN_VALUE), BigInteger.valueOf(Integer.MAX_VALUE));
                yield new SnmpValue.Integer32(n.intValue());
            }
            case UNSIGNED32, GAUGE32 -> new SnmpValue.Gauge32(unsigned(s, type, MAX_UNSIGNED_32).longValue());
            case COUNTER32 -> new SnmpValue.Counter32(unsigned(s, type, MAX_UNSIGNED_32).longValue());
            case TIMETICKS -> new SnmpValue.TimeTicks(unsigned(s, type, MAX_UNSIGNED_32).longValue());
            case COUNTER64 -> new SnmpValue.Counter64(unsigned(s, type, MAX_UNSIGNED_64));
            case OCTET_STRING -> {
                byte[] bytes = hex ? hex(s) : text.getBytes(StandardCharsets.UTF_8);
                checkSize(bytes.length, type);
                yield new SnmpValue.OctetString(bytes);
            }
            case OPAQUE -> new SnmpValue.Opaque(hex(s));
            case BITS -> {
                byte[] bytes = bits(s, type);
                checkSize(bytes.length, type);
                yield new SnmpValue.OctetString(bytes);
            }
            case OBJECT_IDENTIFIER -> new SnmpValue.ObjectId(oid(s, model));
            case IP_ADDRESS, NETWORK_ADDRESS -> new SnmpValue.IpAddress(ipAddress(s));
            default -> throw new IllegalArgumentException(base.text() + " values cannot be set");
        };
    }

    /** Input hint for a type, e.g. {@code up(1), down(2)} or {@code 0..255 characters}. */
    public static String hint(BaseType base, Optional<MibType> type, boolean hex) {
        if (type.isPresent() && !type.get().namedValues().isEmpty()) {
            String values = type.get().namedValues().stream().map(MibType.NamedValue::toString)
                    .collect(Collectors.joining(", "));
            return base == BaseType.BITS ? "Bit names separated by commas: " + values : "One of: " + values;
        }
        String constraint = type.map(t -> !t.ranges().isEmpty() ? "Range " + ranges(t.ranges())
                : !t.sizes().isEmpty() ? "Size " + ranges(t.sizes()) + " octets" : "").orElse("");
        String format = switch (base) {
            case INTEGER, INTEGER32, UNSIGNED32, GAUGE32, COUNTER32, COUNTER64 -> "A number";
            case TIMETICKS -> "Hundredths of a second";
            case OCTET_STRING -> hex ? "Hex octets, e.g. 0a 1b 2c or 0x0a1b2c" : "Text";
            case OPAQUE -> "Hex octets";
            case OBJECT_IDENTIFIER -> "Numeric OID or name, e.g. 1.3.6.1.2.1 or sysUpTime.0";
            case IP_ADDRESS, NETWORK_ADDRESS -> "IPv4 address, e.g. 192.0.2.1";
            default -> "";
        };
        return constraint.isEmpty() ? format : format + "; " + constraint;
    }

    // ---------------------------------------------------------------------------------------------------------

    private static BigInteger integer(String s, Optional<MibType> type) {
        List<MibType.NamedValue> labels = type.map(MibType::namedValues).orElse(List.of());
        String label = s.replaceFirst("\\(\\s*-?\\d+\\s*\\)$", "").strip();
        for (MibType.NamedValue v : labels) {
            if (v.name().equals(label)) {
                return v.number();
            }
        }
        BigInteger n;
        try {
            n = new BigInteger(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(labels.isEmpty() ? "'" + s + "' is not a number"
                    : "'" + s + "' is not one of " + labels.stream().map(MibType.NamedValue::name)
                            .collect(Collectors.joining(", ")));
        }
        if (!labels.isEmpty() && labels.stream().noneMatch(v -> v.number().equals(n))) {
            throw new IllegalArgumentException(n + " is not a defined value; use one of "
                    + labels.stream().map(MibType.NamedValue::toString).collect(Collectors.joining(", ")));
        }
        return n;
    }

    private static BigInteger unsigned(String s, Optional<MibType> type, BigInteger max) {
        BigInteger n = integer(s, type);
        checkRange(n, type, BigInteger.ZERO, max);
        return n;
    }

    private static void checkRange(BigInteger n, Optional<MibType> type, BigInteger min, BigInteger max) {
        if (n.compareTo(min) < 0 || n.compareTo(max) > 0) {
            throw new IllegalArgumentException(n + " is out of range " + min + ".." + max);
        }
        List<MibType.ValueRange> ranges = type.map(MibType::ranges).orElse(List.of());
        if (!ranges.isEmpty() && ranges.stream().noneMatch(r -> n.compareTo(r.min()) >= 0 && n.compareTo(r.max()) <= 0)) {
            throw new IllegalArgumentException(n + " is outside the allowed range " + ranges(ranges));
        }
    }

    private static void checkSize(int length, Optional<MibType> type) {
        List<MibType.ValueRange> sizes = type.map(MibType::sizes).orElse(List.of());
        BigInteger n = BigInteger.valueOf(length);
        if (!sizes.isEmpty() && sizes.stream().noneMatch(r -> n.compareTo(r.min()) >= 0 && n.compareTo(r.max()) <= 0)) {
            throw new IllegalArgumentException(length + (length == 1 ? " octet is" : " octets are")
                    + " outside the allowed size " + ranges(sizes));
        }
    }

    private static String ranges(List<MibType.ValueRange> ranges) {
        return ranges.stream().map(MibType.ValueRange::toString).collect(Collectors.joining(" | "));
    }

    static byte[] hex(String s) {
        String digits = s.replaceFirst("^0[xX]", "").replaceAll("[\\s:.-]", "");
        if (digits.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex input needs an even number of digits");
        }
        try {
            return HexFormat.of().parseHex(digits);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + s + "' is not valid hex");
        }
    }

    private static byte[] bits(String s, Optional<MibType> type) {
        List<MibType.NamedValue> names = type.map(MibType::namedValues).orElse(List.of());
        String[] parts = s.replaceAll("[{}]", "").split("[\\s,|]+");
        int max = -1;
        BitSet set = new BitSet();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            String label = part.replaceFirst("\\(\\d+\\)$", "");
            Optional<Integer> bit = names.stream().filter(v -> v.name().equals(label)).map(v -> v.number().intValue())
                    .findFirst();
            int b;
            if (bit.isPresent()) {
                b = bit.get();
            } else {
                try {
                    b = Integer.parseInt(part);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("'" + part + "' is not a bit name");
                }
                if (!names.isEmpty() && names.stream().noneMatch(v -> v.number().intValue() == b)) {
                    throw new IllegalArgumentException("Bit " + b + " is not defined");
                }
            }
            set.set(b);
            max = Math.max(max, b);
        }
        if (max < 0) {
            return new byte[0];
        }
        byte[] bytes = new byte[max / 8 + 1];
        for (int b = set.nextSetBit(0); b >= 0; b = set.nextSetBit(b + 1)) {
            bytes[b / 8] |= (byte) (0x80 >> (b % 8));
        }
        return bytes;
    }

    private static Oid oid(String s, MibModel model) {
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Enter an OID");
        }
        if (Character.isDigit(s.charAt(0)) || s.charAt(0) == '.') {
            try {
                return Oid.parse(s);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'" + s + "' is not a valid OID");
            }
        }
        int dot = s.indexOf('.', s.indexOf("::") + 1);
        String name = dot < 0 ? s : s.substring(0, dot);
        Optional<MibNode> node = model.lookup(name);
        if (node.isEmpty()) {
            throw new IllegalArgumentException("Unknown name '" + name + "'");
        }
        if (dot < 0) {
            return node.get().oid();
        }
        try {
            Oid suffix = Oid.parse(s.substring(dot + 1));
            Oid result = node.get().oid();
            for (long arc : suffix.arcs()) {
                result = result.child(arc);
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + s.substring(dot + 1) + "' is not a valid OID suffix");
        }
    }

    private static byte[] ipAddress(String s) {
        String[] parts = s.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("'" + s + "' is not an IPv4 address");
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    throw new NumberFormatException();
                }
                bytes[i] = (byte) v;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + s + "' is not an IPv4 address");
            }
        }
        return bytes;
    }
}
