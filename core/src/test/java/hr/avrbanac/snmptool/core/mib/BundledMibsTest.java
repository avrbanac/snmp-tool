package hr.avrbanac.snmptool.core.mib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hr.avrbanac.snmptool.core.mib.model.BaseType;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.model.MibModule;
import hr.avrbanac.snmptool.core.mib.model.MibNode;
import hr.avrbanac.snmptool.core.mib.model.MibType;
import hr.avrbanac.snmptool.core.mib.model.NodeKind;
import hr.avrbanac.snmptool.core.mib.model.Oid;
import hr.avrbanac.snmptool.core.mib.model.OidTree;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The bundled IETF MIBs must all load cleanly; they also serve as the main regression corpus. */
class BundledMibsTest {

    private static final MibModel MODEL = MibLoader.load(BundledMibs.ietf());

    @Test
    void loadsAllModulesWithoutDiagnostics() {
        assertEquals(List.of(), MODEL.diagnostics());
        assertEquals(BundledMibs.ietf().size(), MODEL.modules().size());
    }

    @ParameterizedTest
    @CsvSource({
        "SNMPv2-MIB::sysDescr,           1.3.6.1.2.1.1.1",
        "IF-MIB::ifIndex,                1.3.6.1.2.1.2.2.1.1",
        "IF-MIB::ifHCInOctets,           1.3.6.1.2.1.31.1.1.1.6",
        "SNMPv2-SMI::enterprises,        1.3.6.1.4.1",
        "SNMPv2-SMI::zeroDotZero,        0.0",
        "SNMP-FRAMEWORK-MIB::snmpEngineID, 1.3.6.1.6.3.10.2.1.1",
        "IF-MIB::linkDown,               1.3.6.1.6.3.1.1.5.3",
        "HOST-RESOURCES-MIB::hrSWRunName, 1.3.6.1.2.1.25.4.2.1.2",
        "IP-MIB::ipAddressAddr,          1.3.6.1.2.1.4.34.1.2",
        "RFC1213-MIB::egpNeighState,     1.3.6.1.2.1.8.5.1.1",
    })
    void resolvesKnownObjectIdentifiers(String name, String oid) {
        assertEquals(Oid.parse(oid), MODEL.lookup(name).orElseThrow().oid());
    }

    @Test
    void classifiesIfTable() {
        assertEquals(NodeKind.TABLE, MODEL.lookup("IF-MIB::ifTable").orElseThrow().kind());
        assertEquals(NodeKind.ROW, MODEL.lookup("IF-MIB::ifEntry").orElseThrow().kind());
        assertEquals(NodeKind.COLUMN, MODEL.lookup("IF-MIB::ifDescr").orElseThrow().kind());
        assertEquals(NodeKind.SCALAR, MODEL.lookup("IF-MIB::ifNumber").orElseThrow().kind());
        assertEquals(List.of(new MibNode.IndexItem("ifIndex", false)), MODEL.lookup("IF-MIB::ifEntry").orElseThrow().index());
        assertEquals(Optional.of("ifEntry"), MODEL.lookup("IF-MIB::ifXEntry").orElseThrow().augments());
    }

    @Test
    void resolvesSmiTypes() {
        MibType ifIndex = MODEL.lookup("IF-MIB::ifIndex").orElseThrow().syntax().orElseThrow();
        assertEquals("InterfaceIndex", ifIndex.name());
        assertEquals(BaseType.INTEGER32, ifIndex.baseType());
        assertEquals(Optional.of("d"), ifIndex.displayHint());

        MibType ifType = MODEL.lookup("IF-MIB::ifType").orElseThrow().syntax().orElseThrow();
        assertEquals("IANAifType", ifType.name());
        assertEquals(Optional.of("ethernetCsmacd"), ifType.label(BigInteger.valueOf(6)));

        assertEquals(BaseType.COUNTER64,
                MODEL.lookup("IF-MIB::ifHCInOctets").orElseThrow().syntax().orElseThrow().baseType());
        assertEquals(BaseType.TIMETICKS,
                MODEL.lookup("SNMPv2-MIB::sysUpTime").orElseThrow().syntax().orElseThrow().baseType());
        assertEquals(BaseType.NETWORK_ADDRESS,
                MODEL.lookup("RFC1213-MIB::atNetAddress").orElseThrow().syntax().orElseThrow().baseType());
        assertEquals(Optional.of("1x:"),
                MODEL.lookup("IF-MIB::ifPhysAddress").orElseThrow().syntax().orElseThrow().displayHint());
    }

    @Test
    void prefersSmiV2DefinitionsInTheTree() {
        OidTree.Node sysDescr = MODEL.tree().find(Oid.parse("1.3.6.1.2.1.1.1")).orElseThrow();
        assertEquals("SNMPv2-MIB", sysDescr.definition().orElseThrow().module());
        assertEquals(2, sysDescr.definitions().size());
    }

    @Test
    void namesInstanceOids() {
        OidTree.Node node = MODEL.tree().longestPrefix(Oid.parse("1.3.6.1.2.1.2.2.1.2.3")).orElseThrow();
        assertEquals("ifDescr", node.label());
    }

    @Test
    void treeStartsAtStandardRoots() {
        assertEquals(List.of("ccitt", "iso", "joint-iso-ccitt"),
                MODEL.tree().roots().stream().map(OidTree.Node::label).toList());
    }

    @Test
    void readsModuleMetadata() {
        MibModule ifMib = MODEL.module("IF-MIB").orElseThrow();
        assertEquals(MibModule.SmiVersion.V2, ifMib.smiVersion());
        assertTrue(ifMib.lastUpdated().isPresent());
        assertEquals(3, ifMib.revisions().size());
        assertTrue(ifMib.dependencies().contains("SNMPv2-SMI"));
        assertEquals(MibModule.SmiVersion.V1, MODEL.module("RFC1213-MIB").orElseThrow().smiVersion());
    }
}
