package hr.avrbanac.snmptool.core.mib.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hr.avrbanac.snmptool.core.mib.BundledMibs;
import hr.avrbanac.snmptool.core.mib.MibLoader;
import hr.avrbanac.snmptool.core.mib.MibSource;
import hr.avrbanac.snmptool.core.mib.model.BaseType;
import hr.avrbanac.snmptool.core.mib.model.Diagnostic;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.model.MibNode;
import hr.avrbanac.snmptool.core.mib.model.MibType;
import hr.avrbanac.snmptool.core.mib.model.NodeKind;
import hr.avrbanac.snmptool.core.mib.model.Oid;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResolverTest {

    private static final Set<String> BASE = Set.of("SNMPv2-SMI.mib", "SNMPv2-TC.mib", "SNMPv2-CONF.mib",
            "RFC1155-SMI.mib", "RFC-1212.mib", "RFC-1215.mib", "RFC1213-MIB.mib");

    private static MibModel load(String... texts) {
        List<MibSource> sources = new ArrayList<>(BundledMibs.ietf().stream()
                .filter(s -> BASE.contains(s.name())).toList());
        for (int i = 0; i < texts.length; i++) {
            sources.add(new MibSource("test" + i + ".mib", texts[i]));
        }
        return MibLoader.load(sources);
    }

    private static List<String> messages(MibModel model) {
        return model.diagnostics().stream().map(Diagnostic::message).toList();
    }

    private static final String TEST_MIB = """
            TEST-MIB DEFINITIONS ::= BEGIN
            IMPORTS
                MODULE-IDENTITY, OBJECT-TYPE, NOTIFICATION-TYPE, Integer32, enterprises FROM SNMPv2-SMI
                TEXTUAL-CONVENTION, DisplayString, RowStatus FROM SNMPv2-TC;

            testMIB MODULE-IDENTITY
                LAST-UPDATED "202601010000Z"
                ORGANIZATION "Org"
                CONTACT-INFO "Contact"
                DESCRIPTION  "Test module."
                ::= { enterprises 9999 }

            -- forward reference: testObjects is defined below its first use
            testTable OBJECT-TYPE
                SYNTAX      SEQUENCE OF TestEntry
                MAX-ACCESS  not-accessible
                STATUS      current
                DESCRIPTION "Table."
                ::= { testObjects 1 }

            testObjects OBJECT IDENTIFIER ::= { testMIB 1 }

            testEntry OBJECT-TYPE
                SYNTAX      TestEntry
                MAX-ACCESS  not-accessible
                STATUS      current
                DESCRIPTION "Row."
                INDEX       { testIndex }
                ::= { testTable 1 }

            TestEntry ::= SEQUENCE { testIndex Integer32, testName ShortName, testStatus RowStatus }

            ShortName ::= TEXTUAL-CONVENTION
                STATUS      current
                DESCRIPTION "Short name."
                SYNTAX      DisplayString (SIZE (0..32))

            testIndex OBJECT-TYPE
                SYNTAX      Integer32 (1..MAX)
                MAX-ACCESS  not-accessible
                STATUS      current
                DESCRIPTION "Index."
                ::= { testEntry 1 }

            testName OBJECT-TYPE
                SYNTAX      ShortName (SIZE (1..16))
                MAX-ACCESS  read-create
                STATUS      current
                DESCRIPTION "Name."
                ::= { testEntry 2 }

            testStatus OBJECT-TYPE
                SYNTAX      RowStatus
                MAX-ACCESS  read-create
                STATUS      current
                DESCRIPTION "Status."
                ::= { testEntry 3 }

            testScalar OBJECT-TYPE
                SYNTAX      INTEGER { on(1), off(2) }
                MAX-ACCESS  read-write
                STATUS      current
                DESCRIPTION "Scalar."
                DEFVAL      { off }
                ::= { testObjects 2 }

            testEvent NOTIFICATION-TYPE
                OBJECTS     { testName }
                STATUS      current
                DESCRIPTION "Event."
                ::= { testMIB 0 1 }
            END
            """;

    @Test
    void resolvesObjectIdentifiersIncludingForwardReferences() {
        MibModel model = load(TEST_MIB);
        assertEquals(List.of(), model.diagnostics());
        assertEquals(Oid.parse("1.3.6.1.4.1.9999"), model.find("TEST-MIB", "testMIB").orElseThrow().oid());
        assertEquals(Oid.parse("1.3.6.1.4.1.9999.1.1.1.2"), model.find("TEST-MIB", "testName").orElseThrow().oid());
        assertEquals(Oid.parse("1.3.6.1.4.1.9999.0.1"), model.find("TEST-MIB", "testEvent").orElseThrow().oid());
    }

    @Test
    void classifiesNodes() {
        MibModel model = load(TEST_MIB);
        assertEquals(NodeKind.MODULE_IDENTITY, model.lookup("testMIB").orElseThrow().kind());
        assertEquals(NodeKind.OBJECT_IDENTIFIER, model.lookup("testObjects").orElseThrow().kind());
        assertEquals(NodeKind.TABLE, model.lookup("testTable").orElseThrow().kind());
        assertEquals(NodeKind.ROW, model.lookup("testEntry").orElseThrow().kind());
        assertEquals(NodeKind.COLUMN, model.lookup("testName").orElseThrow().kind());
        assertEquals(NodeKind.SCALAR, model.lookup("testScalar").orElseThrow().kind());
        assertEquals(NodeKind.NOTIFICATION, model.lookup("testEvent").orElseThrow().kind());
    }

    @Test
    void resolvesTypesThroughTextualConventions() {
        MibModel model = load(TEST_MIB);
        MibType name = model.lookup("testName").orElseThrow().syntax().orElseThrow();
        assertEquals("ShortName (SIZE (1..16))", name.syntaxText());
        assertEquals(BaseType.OCTET_STRING, name.baseType());
        assertEquals(Optional.of("255a"), name.displayHint());
        assertEquals(List.of(new MibType.ValueRange(BigInteger.ONE, BigInteger.valueOf(16))), name.sizes());

        MibType shortName = name.parent().orElseThrow();
        assertEquals("ShortName", shortName.name());
        assertEquals("DisplayString", shortName.parent().orElseThrow().name());
        assertTrue(shortName.textualConvention().isPresent());

        MibType index = model.lookup("testIndex").orElseThrow().syntax().orElseThrow();
        assertEquals(BaseType.INTEGER32, index.baseType());
        assertEquals("Integer32 (1..2147483647)", index.syntaxText());

        MibType status = model.lookup("testStatus").orElseThrow().syntax().orElseThrow();
        assertEquals(Optional.of("active"), status.label(BigInteger.ONE));

        MibNode scalar = model.lookup("testScalar").orElseThrow();
        assertEquals("INTEGER { on(1), off(2) }", scalar.syntax().orElseThrow().syntaxText());
        assertEquals(Optional.of("off"), scalar.defval());
    }

    @Test
    void findsInstancesByLongestPrefix() {
        MibModel model = load(TEST_MIB);
        MibNode node = model.tree().longestPrefix(Oid.parse("1.3.6.1.4.1.9999.1.1.1.2.42"))
                .flatMap(n -> n.definition()).orElseThrow();
        assertEquals("testName", node.name());
    }

    @Test
    void reportsMissingModuleAndKeepsTheRest() {
        MibModel model = load("""
                PARTIAL-MIB DEFINITIONS ::= BEGIN
                IMPORTS enterprises FROM SNMPv2-SMI
                        vendorRoot FROM MISSING-MIB;
                ok OBJECT IDENTIFIER ::= { enterprises 1 }
                orphan OBJECT IDENTIFIER ::= { vendorRoot 1 }
                END
                """);
        assertEquals(List.of("Module PARTIAL-MIB imports from MISSING-MIB, which is not loaded",
                "Unknown object identifier vendorRoot in module PARTIAL-MIB"), messages(model));
        assertTrue(model.find("PARTIAL-MIB", "ok").isPresent());
        assertTrue(model.find("PARTIAL-MIB", "orphan").isEmpty());
    }

    @Test
    void usesDefinitionsThatWereNotImported() {
        MibModel model = load("""
                LAZY-MIB DEFINITIONS ::= BEGIN
                lazy OBJECT IDENTIFIER ::= { enterprises 1 }
                lazyValue OBJECT-TYPE
                    SYNTAX      Counter32
                    MAX-ACCESS  read-only
                    STATUS      current
                    DESCRIPTION "x"
                    ::= { lazy 1 }
                END
                """);
        assertEquals(List.of(
                "enterprises is not imported into LAZY-MIB; using the definition from SNMPv2-SMI",
                "Counter32 is not imported into LAZY-MIB; using the definition from SNMPv2-SMI"), messages(model));
        assertEquals(BaseType.COUNTER32, model.lookup("lazyValue").orElseThrow().syntax().orElseThrow().baseType());
    }

    @Test
    void placesSmiV1TrapsUnderEnterprise() {
        MibModel model = load("""
                TRAP-MIB DEFINITIONS ::= BEGIN
                IMPORTS enterprises FROM RFC1155-SMI
                        TRAP-TYPE FROM RFC-1215;
                vendor OBJECT IDENTIFIER ::= { enterprises 4242 }
                vendorTrap TRAP-TYPE
                    ENTERPRISE  vendor
                    DESCRIPTION "Trap."
                    ::= 7
                END
                """);
        assertEquals(List.of(), model.diagnostics());
        MibNode trap = model.lookup("vendorTrap").orElseThrow();
        assertEquals(NodeKind.TRAP, trap.kind());
        assertEquals(Oid.parse("1.3.6.1.4.1.4242.0.7"), trap.oid());
    }

    @Test
    void reportsCircularDefinitions() {
        MibModel model = load("""
                LOOP-MIB DEFINITIONS ::= BEGIN
                a OBJECT IDENTIFIER ::= { b 1 }
                b OBJECT IDENTIFIER ::= { a 1 }
                Loop ::= Loop
                END
                """);
        assertTrue(messages(model).contains("Circular object identifier definition of a"));
        assertTrue(messages(model).contains("Circular type definition of Loop"));
    }

    @Test
    void acceptsCommonVendorMistakes() {
        MibModel model = load("""
                SLOPPY-MIB DEFINITIONS ::= BEGIN
                IMPORTS enterprises FROM RFC1155-SMI
                        OBJECT-TYPE FROM RFC1212
                        DisplayString FROM RFC-1213;
                sloppy OBJECT IDENTIFIER ::= { enterprises 777 }
                sloppyTable OBJECT-TYPE
                    SYNTAX SEQUENCE OF sloppyEntry
                    ACCESS not-accessible
                    STATUS mandatory
                    ::= { sloppy 1 }
                sloppyEntry OBJECT-TYPE
                    SYNTAX SloppyEntry
                    ACCESS not-accessible
                    STATUS mandatory
                    INDEX { sloppyIndex }
                    ::= { sloppyTable 1 }
                SloppyEntry ::= SEQUENCE { sloppyIndex INTEGER32, sloppyCount GAUGE, sloppyName DisplayString }
                sloppyIndex OBJECT-TYPE
                    SYNTAX INTEGER32
                    ACCESS read-only
                    STATUS mandatory
                    ::= { sloppyEntry 1 }
                sloppyCount OBJECT-TYPE
                    SYNTAX GAUGE
                    ACCESS read-only
                    STATUS mandatory
                    ::= { sloppyEntry 2 }
                sloppyName OBJECT-TYPE
                    SYNTAX DisplayString
                    ACCESS read-only
                    STATUS mandatory
                    ::= { sloppyEntry 3 }
                END
                """);
        assertEquals(List.of(
                "Module SLOPPY-MIB imports from RFC1212, which is not loaded; using RFC-1212",
                "Module SLOPPY-MIB imports from RFC-1213, which is not loaded; using RFC1213-MIB",
                "Type sloppyEntry should be written SloppyEntry",
                "Type INTEGER32 should be written Integer32; using the SMI definition",
                "Type GAUGE should be written Gauge; using the SMI definition"), messages(model));
        MibNode table = model.lookup("sloppyTable").orElseThrow();
        assertEquals(NodeKind.TABLE, table.kind());
        assertEquals("SEQUENCE OF SloppyEntry", table.syntax().orElseThrow().syntaxText());
        assertEquals(BaseType.INTEGER32, model.lookup("sloppyIndex").orElseThrow().syntax().orElseThrow().baseType());
        assertEquals(BaseType.GAUGE32, model.lookup("sloppyCount").orElseThrow().syntax().orElseThrow().baseType());
        assertEquals("DisplayString", model.lookup("sloppyName").orElseThrow().syntax().orElseThrow().name());
        assertEquals(NodeKind.COLUMN, model.lookup("sloppyName").orElseThrow().kind());
    }

    @Test
    void keepsReportingNonTypesUsedAsTypes() {
        MibModel model = load("""
                NOT-TYPE-MIB DEFINITIONS ::= BEGIN
                IMPORTS enterprises, OBJECT-TYPE FROM SNMPv2-SMI;
                thing OBJECT IDENTIFIER ::= { enterprises 778 }
                value OBJECT-TYPE
                    SYNTAX thing
                    MAX-ACCESS read-only
                    STATUS current
                    DESCRIPTION "x"
                    ::= { thing 1 }
                END
                """);
        assertEquals(List.of("thing is not a type"), messages(model));
    }

    @Test
    void ignoresDuplicateModules() {
        MibModel model = load("""
                DUP-MIB DEFINITIONS ::= BEGIN
                first OBJECT IDENTIFIER ::= { iso 3 }
                END
                """, """
                DUP-MIB DEFINITIONS ::= BEGIN
                second OBJECT IDENTIFIER ::= { iso 4 }
                END
                """);
        assertTrue(model.lookup("first").isPresent());
        assertTrue(model.lookup("second").isEmpty());
        assertEquals(1, model.diagnostics().size());
    }
}
