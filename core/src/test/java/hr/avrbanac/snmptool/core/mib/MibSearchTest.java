package hr.avrbanac.snmptool.core.mib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.model.Oid;
import hr.avrbanac.snmptool.core.mib.model.OidTree;
import java.util.List;
import org.junit.jupiter.api.Test;

class MibSearchTest {

    private static final MibModel MODEL = MibLoader.load(BundledMibs.ietf());

    private static List<String> labels(MibSearch.Result result) {
        return result.nodes().stream().map(OidTree.Node::label).toList();
    }

    @Test
    void blankQueryFindsNothing() {
        assertEquals(List.of(), MibSearch.search(MODEL, "  ", true, 100).nodes());
    }

    @Test
    void findsByNameSubstringIgnoringCase() {
        List<String> labels = labels(MibSearch.search(MODEL, "IFHCINOCT", false, 100));
        assertEquals(List.of("ifHCInOctets"), labels);
    }

    @Test
    void findsByNumericOidAndInstanceOid() {
        assertEquals(List.of("ifDescr"), labels(MibSearch.search(MODEL, "1.3.6.1.2.1.2.2.1.2", false, 100)));
        assertEquals(List.of("ifDescr"), labels(MibSearch.search(MODEL, ".1.3.6.1.2.1.2.2.1.2.7", false, 100)));
    }

    @Test
    void findsByQualifiedName() {
        MibSearch.Result result = MibSearch.search(MODEL, "IF-MIB::ifIndex", false, 100);
        assertEquals(Oid.parse("1.3.6.1.2.1.2.2.1.1"), result.nodes().getFirst().oid());
    }

    @Test
    void searchesDescriptionsOnlyWhenAsked() {
        // In IF-MIB this phrase is split across two lines of the ifXEntry description.
        String phrase = "additional management information applicable";
        assertEquals(List.of(), labels(MibSearch.search(MODEL, phrase, false, 100)));
        assertEquals(List.of("ifXEntry"), labels(MibSearch.search(MODEL, phrase, true, 100)));
    }

    @Test
    void returnsResultsInOidOrderAndRespectsLimit() {
        MibSearch.Result result = MibSearch.search(MODEL, "if", false, 5);
        assertEquals(5, result.nodes().size());
        assertTrue(result.truncated());
        List<Oid> oids = result.nodes().stream().map(OidTree.Node::oid).toList();
        assertEquals(oids.stream().sorted().toList(), oids);
        assertFalse(MibSearch.search(MODEL, "ifHCInOctets", false, 5).truncated());
    }

    @Test
    void buildsSymbolicPath() {
        OidTree.Node node = MODEL.tree().find(Oid.parse("1.3.6.1.2.1.2.2.1.2")).orElseThrow();
        assertEquals("iso.org.dod.internet.mgmt.mib-2.interfaces.ifTable.ifEntry.ifDescr", node.symbolicPath());
    }
}
