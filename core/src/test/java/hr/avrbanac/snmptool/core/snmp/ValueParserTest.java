package hr.avrbanac.snmptool.core.snmp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hr.avrbanac.snmptool.core.mib.BundledMibs;
import hr.avrbanac.snmptool.core.mib.MibLoader;
import hr.avrbanac.snmptool.core.mib.model.BaseType;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.model.MibType;
import hr.avrbanac.snmptool.core.mib.model.Oid;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ValueParserTest {

    private static final MibModel MODEL = MibLoader.load(BundledMibs.ietf());

    private static MibType syntax(String name) {
        return MODEL.lookup(name).orElseThrow().syntax().orElseThrow();
    }

    private static SnmpValue parse(String text, String object) {
        MibType type = syntax(object);
        return ValueParser.parse(text, type.baseType(), Optional.of(type), false, MODEL);
    }

    private static String error(String text, String object) {
        return assertThrows(IllegalArgumentException.class, () -> parse(text, object)).getMessage();
    }

    @Test
    void parsesEnumerationsByLabelOrNumber() {
        assertEquals(new SnmpValue.Integer32(2), parse("down", "IF-MIB::ifAdminStatus"));
        assertEquals(new SnmpValue.Integer32(2), parse("down(2)", "IF-MIB::ifAdminStatus"));
        assertEquals(new SnmpValue.Integer32(1), parse("1", "IF-MIB::ifAdminStatus"));
        assertTrue(error("sideways", "IF-MIB::ifAdminStatus").contains("up, down, testing"));
        assertTrue(error("9", "IF-MIB::ifAdminStatus").contains("not a defined value"));
    }

    @Test
    void checksRanges() {
        assertEquals(new SnmpValue.Integer32(72), parse("72", "SNMPv2-MIB::sysServices"));
        assertTrue(error("200", "SNMPv2-MIB::sysServices").contains("0..127"));
        assertTrue(error("abc", "SNMPv2-MIB::sysServices").contains("not a number"));
    }

    @Test
    void parsesTextAndChecksSize() {
        assertEquals(new SnmpValue.OctetString("Lab rack 4".getBytes(StandardCharsets.UTF_8)),
                parse("Lab rack 4", "SNMPv2-MIB::sysLocation"));
        assertTrue(error("x".repeat(256), "SNMPv2-MIB::sysLocation").contains("0..255"));
    }

    @Test
    void parsesHexOctets() {
        MibType mac = syntax("IF-MIB::ifPhysAddress");
        assertTrue(ValueParser.prefersHex(mac));
        SnmpValue v = ValueParser.parse("00:1b:21:3a:4f:5e", BaseType.OCTET_STRING, Optional.of(mac), true, MODEL);
        assertArrayEquals(new byte[] {0, 0x1b, 0x21, 0x3a, 0x4f, 0x5e}, ((SnmpValue.OctetString) v).bytes());
        assertArrayEquals(new byte[] {0x0a, 0x1b}, ValueParser.hex("0x0a1b"));
        assertThrows(IllegalArgumentException.class, () -> ValueParser.hex("abc"));
    }

    @Test
    void parsesBits() {
        SnmpValue v = parse("existence, threshold", "DISMAN-EVENT-MIB::mteTriggerTest");
        assertArrayEquals(new byte[] {(byte) 0xA0}, ((SnmpValue.OctetString) v).bytes());
        assertArrayEquals(new byte[0], ((SnmpValue.OctetString) parse("", "DISMAN-EVENT-MIB::mteTriggerTest")).bytes());
        assertThrows(IllegalArgumentException.class, () -> parse("bogus", "DISMAN-EVENT-MIB::mteTriggerTest"));
    }

    @Test
    void parsesOidsNumericallyOrByName() {
        assertEquals(new SnmpValue.ObjectId(Oid.parse("1.3.6.1.2.1.1.3.0")),
                ValueParser.parse("sysUpTime.0", BaseType.OBJECT_IDENTIFIER, Optional.empty(), false, MODEL));
        assertEquals(new SnmpValue.ObjectId(Oid.parse("1.3.6.1.4.1.1")),
                ValueParser.parse(".1.3.6.1.4.1.1", BaseType.OBJECT_IDENTIFIER, Optional.empty(), false, MODEL));
        assertThrows(IllegalArgumentException.class,
                () -> ValueParser.parse("noSuchThing", BaseType.OBJECT_IDENTIFIER, Optional.empty(), false, MODEL));
    }

    @Test
    void parsesOtherTypesWithoutMibInformation() {
        assertEquals(new SnmpValue.IpAddress(new byte[] {(byte) 192, 0, 2, 1}),
                ValueParser.parse("192.0.2.1", BaseType.IP_ADDRESS, Optional.empty(), false, MODEL));
        assertThrows(IllegalArgumentException.class,
                () -> ValueParser.parse("192.0.2.300", BaseType.IP_ADDRESS, Optional.empty(), false, MODEL));
        assertEquals(new SnmpValue.Gauge32(4294967295L),
                ValueParser.parse("4294967295", BaseType.GAUGE32, Optional.empty(), false, MODEL));
        assertThrows(IllegalArgumentException.class,
                () -> ValueParser.parse("-1", BaseType.UNSIGNED32, Optional.empty(), false, MODEL));
        assertEquals(new SnmpValue.Counter64(new BigInteger("18446744073709551615")),
                ValueParser.parse("18446744073709551615", BaseType.COUNTER64, Optional.empty(), false, MODEL));
        assertEquals(new SnmpValue.TimeTicks(100),
                ValueParser.parse("100", BaseType.TIMETICKS, Optional.empty(), false, MODEL));
    }

    @Test
    void describesExpectedInput() {
        MibType status = syntax("IF-MIB::ifAdminStatus");
        assertEquals("One of: up(1), down(2), testing(3)",
                ValueParser.hint(status.baseType(), Optional.of(status), false));
        MibType location = syntax("SNMPv2-MIB::sysLocation");
        assertEquals("Text; Size 0..255 octets", ValueParser.hint(location.baseType(), Optional.of(location), false));
    }
}
