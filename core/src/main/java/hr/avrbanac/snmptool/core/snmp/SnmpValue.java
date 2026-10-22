package hr.avrbanac.snmptool.core.snmp;

import hr.avrbanac.snmptool.core.mib.model.Oid;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * A value as carried in a variable binding, independent of the SNMP library.
 */
public sealed interface SnmpValue {

    /** SMI name of the wire type, e.g. {@code OCTET STRING} or {@code Counter32}. */
    String typeName();

    /** Whether this is one of the v2 exception values (noSuchObject, noSuchInstance, endOfMibView). */
    default boolean isException() {
        return false;
    }

    record Integer32(int value) implements SnmpValue {
        @Override
        public String typeName() {
            return "INTEGER";
        }
    }

    /** Arbitrary octets; text, binary or anything a display hint describes. */
    record OctetString(byte[] bytes) implements SnmpValue {
        public OctetString {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        /** Number of octets. */
        public int length() {
            return bytes.length;
        }

        /** Octet at the given position, as 0..255. */
        public int octet(int index) {
            return bytes[index] & 0xFF;
        }

        @Override
        public String typeName() {
            return "OCTET STRING";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof OctetString other && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "OctetString[" + HexFormat.ofDelimiter(":").formatHex(bytes) + "]";
        }
    }

    record ObjectId(Oid oid) implements SnmpValue {
        @Override
        public String typeName() {
            return "OBJECT IDENTIFIER";
        }
    }

    /** IPv4 address (four octets). */
    record IpAddress(byte[] address) implements SnmpValue {
        public IpAddress {
            if (address.length != 4) {
                throw new IllegalArgumentException("IpAddress needs 4 octets");
            }
            address = address.clone();
        }

        @Override
        public byte[] address() {
            return address.clone();
        }

        @Override
        public String typeName() {
            return "IpAddress";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof IpAddress other && Arrays.equals(address, other.address);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(address);
        }

        @Override
        public String toString() {
            return (address[0] & 0xFF) + "." + (address[1] & 0xFF) + "." + (address[2] & 0xFF) + "." + (address[3] & 0xFF);
        }
    }

    record Counter32(long value) implements SnmpValue {
        @Override
        public String typeName() {
            return "Counter32";
        }
    }

    /** Gauge32, also used on the wire for Unsigned32. */
    record Gauge32(long value) implements SnmpValue {
        @Override
        public String typeName() {
            return "Gauge32";
        }
    }

    /** Hundredths of a second. */
    record TimeTicks(long value) implements SnmpValue {
        @Override
        public String typeName() {
            return "TimeTicks";
        }
    }

    record Counter64(BigInteger value) implements SnmpValue {
        @Override
        public String typeName() {
            return "Counter64";
        }
    }

    record Opaque(byte[] bytes) implements SnmpValue {
        public Opaque {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public String typeName() {
            return "Opaque";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Opaque other && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "Opaque[" + HexFormat.ofDelimiter(":").formatHex(bytes) + "]";
        }
    }

    record Null() implements SnmpValue {
        @Override
        public String typeName() {
            return "NULL";
        }
    }

    record NoSuchObject() implements SnmpValue {
        @Override
        public String typeName() {
            return "noSuchObject";
        }

        @Override
        public boolean isException() {
            return true;
        }
    }

    record NoSuchInstance() implements SnmpValue {
        @Override
        public String typeName() {
            return "noSuchInstance";
        }

        @Override
        public boolean isException() {
            return true;
        }
    }

    record EndOfMibView() implements SnmpValue {
        @Override
        public String typeName() {
            return "endOfMibView";
        }

        @Override
        public boolean isException() {
            return true;
        }
    }
}
