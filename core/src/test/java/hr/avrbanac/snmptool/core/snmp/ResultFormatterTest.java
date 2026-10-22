package hr.avrbanac.snmptool.core.snmp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hr.avrbanac.snmptool.core.mib.BundledMibs;
import hr.avrbanac.snmptool.core.mib.MibLoader;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.model.Oid;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ResultFormatterTest {

    private static final MibModel MODEL = MibLoader.load(BundledMibs.ietf());
    private static final ResultFormatter FORMATTER = new ResultFormatter(MODEL);

    private static Oid oid(String name, String suffix) {
        Oid oid = MODEL.lookup(name).orElseThrow().oid();
        for (long arc : suffix.isEmpty() ? new long[0] : Oid.parse(suffix).arcs()) {
            oid = oid.child(arc);
        }
        return oid;
    }

    private static ResultFormatter.Formatted format(String name, String suffix, SnmpValue value) {
        return FORMATTER.format(new VarBindResult(oid(name, suffix), value));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SNMPv2-MIB::sysDescr          | 0                      | sysDescr.0",
        "IF-MIB::ifDescr               | 3                      | ifDescr.3",
        "IF-MIB::ifName                | 5                      | ifName.5",
        "IP-MIB::ipAdEntAddr           | 10.0.0.1               | ipAdEntAddr.10.0.0.1",
        "SNMP-VIEW-BASED-ACM-MIB::vacmGroupName | 3.6.112.117.98.108.105.99 | vacmGroupName.3.\"public\"",
        "SNMP-TARGET-MIB::snmpTargetAddrTDomain | 97.98.99      | snmpTargetAddrTDomain.\"abc\"",
        "IF-MIB::ifStackStatus         | 1.2                    | ifStackStatus.1.2",
        "IF-MIB::ifDescr               | ''                     | ifDescr",
    })
    void namesInstances(String object, String suffix, String expected) {
        assertEquals(expected, FORMATTER.name(oid(object, suffix)));
    }

    @Test
    void namesUnknownOidsRelativeToDeepestDefinition() {
        assertEquals("enterprises.99999.1.2", FORMATTER.name(Oid.parse("1.3.6.1.4.1.99999.1.2")));
        assertEquals("1.2.3", FORMATTER.name(Oid.parse("1.2.3")));
    }

    @Test
    void keepsUndecodableIndexesNumeric() {
        // ipAdEntAddr needs four arcs for its IpAddress index
        assertEquals("ipAdEntAddr.10.0", FORMATTER.name(oid("IP-MIB::ipAdEntAddr", "10.0")));
    }

    @Test
    void formatsValuesUsingTheMib() {
        assertEquals("up(1)", format("IF-MIB::ifAdminStatus", "1", new SnmpValue.Integer32(1)).value());
        assertEquals("7 (no label)", format("IF-MIB::ifAdminStatus", "1", new SnmpValue.Integer32(7)).value());
        assertEquals("1 day, 02:03:04.05 (9378405)",
                format("SNMPv2-MIB::sysUpTime", "0", new SnmpValue.TimeTicks(9378405)).value());
        assertEquals("00:1b:21:3a:4f:5e", format("IF-MIB::ifPhysAddress", "1",
                new SnmpValue.OctetString(new byte[] {0, 0x1b, 0x21, 0x3a, 0x4f, 0x5e})).value());
        assertEquals("enterprises.8072.3.2.10", format("SNMPv2-MIB::sysObjectID", "0",
                new SnmpValue.ObjectId(Oid.parse("1.3.6.1.4.1.8072.3.2.10"))).value());
        assertEquals("200 milliseconds", format("TCP-MIB::tcpRtoMin", "0", new SnmpValue.Integer32(200)).value());
        assertEquals("{ existence(0), threshold(2) }", format("DISMAN-EVENT-MIB::mteTriggerTest", "1.97",
                new SnmpValue.OctetString(new byte[] {(byte) 0xA0})).value());
        assertEquals("2024-10-22,14:30:15.0,+2:0", format("HOST-RESOURCES-MIB::hrSystemDate", "0",
                new SnmpValue.OctetString(new byte[] {0x07, (byte) 0xe8, 10, 22, 14, 30, 15, 0, '+', 2, 0})).value());
    }

    @Test
    void formatsTextBinaryAndRaw() {
        ResultFormatter.Formatted text = format("SNMPv2-MIB::sysDescr", "0",
                new SnmpValue.OctetString("Linux box".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Linux box", text.value());
        assertEquals("4c 69 6e 75 78 20 62 6f 78", text.raw());
        assertEquals("OCTET STRING", text.type());

        ResultFormatter.Formatted binary = FORMATTER.format(new VarBindResult(Oid.parse("1.3.6.1.4.1.99999.1"),
                new SnmpValue.OctetString(new byte[] {0, 1, (byte) 0xff})));
        assertEquals("00 01 ff", binary.value());

        ResultFormatter.Formatted counter = format("IF-MIB::ifHCInOctets", "1",
                new SnmpValue.Counter64(new BigInteger("18446744073709551615")));
        assertEquals("18446744073709551615", counter.value());
        assertEquals("Counter64", counter.type());

        assertEquals("noSuchInstance", format("IF-MIB::ifDescr", "99", new SnmpValue.NoSuchInstance()).value());
    }

    @Test
    void formatsTimeTicks() {
        assertEquals("00:00:00.00", ResultFormatter.timeTicks(0));
        assertEquals("3 days, 00:00:01.00", ResultFormatter.timeTicks(3 * 8_640_000L + 100));
    }
}
