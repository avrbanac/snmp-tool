package hr.avrbanac.snmptool.core.snmp;

import hr.avrbanac.snmptool.core.mib.model.Oid;
import java.math.BigInteger;
import org.snmp4j.smi.Counter32;
import org.snmp4j.smi.Counter64;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.IpAddress;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Opaque;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.SMIConstants;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.Variable;

/**
 * Conversion between SNMP4J variables and {@link SnmpValue}s.
 */
final class Variables {

    private static final BigInteger UNSIGNED_64 = BigInteger.ONE.shiftLeft(64);

    private Variables() {
    }

    static OID toOid(Oid oid) {
        long[] arcs = oid.arcs();
        int[] values = new int[arcs.length];
        for (int i = 0; i < arcs.length; i++) {
            values[i] = (int) arcs[i];
        }
        return new OID(values);
    }

    static Oid fromOid(OID oid) {
        int[] values = oid.getValue();
        long[] arcs = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            arcs[i] = values[i] & 0xFFFF_FFFFL;
        }
        return Oid.of(arcs);
    }

    static Variable toVariable(SnmpValue value) {
        return switch (value) {
            case SnmpValue.Integer32 v -> new Integer32(v.value());
            case SnmpValue.OctetString v -> new OctetString(v.bytes());
            case SnmpValue.ObjectId v -> toOid(v.oid());
            case SnmpValue.IpAddress v -> new IpAddress(v.address());
            case SnmpValue.Counter32 v -> new Counter32(v.value());
            case SnmpValue.Gauge32 v -> new Gauge32(v.value());
            case SnmpValue.TimeTicks v -> new TimeTicks(v.value());
            case SnmpValue.Counter64 v -> new Counter64(v.value().longValue());
            case SnmpValue.Opaque v -> new Opaque(v.bytes());
            case SnmpValue.Null _ -> new Null();
            case SnmpValue.NoSuchObject _ -> Null.noSuchObject;
            case SnmpValue.NoSuchInstance _ -> Null.noSuchInstance;
            case SnmpValue.EndOfMibView _ -> Null.endOfMibView;
        };
    }

    static SnmpValue fromVariable(Variable variable) {
        return switch (variable.getSyntax()) {
            case SMIConstants.SYNTAX_INTEGER -> new SnmpValue.Integer32(variable.toInt());
            case SMIConstants.SYNTAX_OCTET_STRING -> new SnmpValue.OctetString(((OctetString) variable).getValue());
            case SMIConstants.SYNTAX_OBJECT_IDENTIFIER -> new SnmpValue.ObjectId(fromOid((OID) variable));
            case SMIConstants.SYNTAX_IPADDRESS -> new SnmpValue.IpAddress(((IpAddress) variable).toByteArray());
            case SMIConstants.SYNTAX_COUNTER32 -> new SnmpValue.Counter32(variable.toLong());
            case SMIConstants.SYNTAX_GAUGE32 -> new SnmpValue.Gauge32(variable.toLong());
            case SMIConstants.SYNTAX_TIMETICKS -> new SnmpValue.TimeTicks(variable.toLong());
            case SMIConstants.SYNTAX_COUNTER64 -> {
                BigInteger v = BigInteger.valueOf(variable.toLong());
                yield new SnmpValue.Counter64(v.signum() < 0 ? v.add(UNSIGNED_64) : v);
            }
            case SMIConstants.SYNTAX_OPAQUE -> new SnmpValue.Opaque(((Opaque) variable).getValue());
            case SMIConstants.EXCEPTION_NO_SUCH_OBJECT -> new SnmpValue.NoSuchObject();
            case SMIConstants.EXCEPTION_NO_SUCH_INSTANCE -> new SnmpValue.NoSuchInstance();
            case SMIConstants.EXCEPTION_END_OF_MIB_VIEW -> new SnmpValue.EndOfMibView();
            default -> new SnmpValue.Null();
        };
    }
}
