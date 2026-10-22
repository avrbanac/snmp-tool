package hr.avrbanac.snmptool.core.mib.model;

import java.math.BigInteger;

/**
 * The underlying SMI type that a {@link MibType} resolves to.
 */
public enum BaseType {
    INTEGER("INTEGER", "-2147483648", "2147483647"),
    INTEGER32("Integer32", "-2147483648", "2147483647"),
    UNSIGNED32("Unsigned32", "0", "4294967295"),
    GAUGE32("Gauge32", "0", "4294967295"),
    COUNTER32("Counter32", "0", "4294967295"),
    COUNTER64("Counter64", "0", "18446744073709551615"),
    TIMETICKS("TimeTicks", "0", "4294967295"),
    IP_ADDRESS("IpAddress", null, null),
    OPAQUE("Opaque", null, null),
    OCTET_STRING("OCTET STRING", null, null),
    OBJECT_IDENTIFIER("OBJECT IDENTIFIER", null, null),
    BITS("BITS", null, null),
    /** SMIv1 {@code NetworkAddress}. */
    NETWORK_ADDRESS("NetworkAddress", null, null),
    SEQUENCE("SEQUENCE", null, null),
    SEQUENCE_OF("SEQUENCE OF", null, null),
    CHOICE("CHOICE", null, null),
    NULL("NULL", null, null),
    /** The type could not be resolved. */
    UNKNOWN("?", null, null);

    /** Largest size of an OCTET STRING in SMI. */
    public static final BigInteger MAX_SIZE = BigInteger.valueOf(65535);

    private final String text;
    private final BigInteger min;
    private final BigInteger max;

    BaseType(String text, String min, String max) {
        this.text = text;
        this.min = min == null ? null : new BigInteger(min);
        this.max = max == null ? null : new BigInteger(max);
    }

    /** Name as written in a MIB. */
    public String text() {
        return text;
    }

    /** Smallest value of a numeric type; used for {@code MIN}. */
    public BigInteger min() {
        return min == null ? INTEGER.min : min;
    }

    /** Largest value of a numeric type; used for {@code MAX}. */
    public BigInteger max() {
        return max == null ? INTEGER.max : max;
    }

    /** Whether values of this type are numbers. */
    public boolean isNumeric() {
        return min != null;
    }
}
