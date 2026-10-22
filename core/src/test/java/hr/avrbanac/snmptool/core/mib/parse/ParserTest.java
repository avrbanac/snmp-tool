package hr.avrbanac.snmptool.core.mib.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hr.avrbanac.snmptool.core.mib.model.Diagnostic;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ParserTest {

    private final Diagnostics diagnostics = new Diagnostics();

    private List<Ast.Module> parse(String text) {
        return Parser.parse(text, "test", diagnostics);
    }

    private Ast.Module parseOne(String body) {
        List<Ast.Module> modules = parse("TEST-MIB DEFINITIONS ::= BEGIN\n" + body + "\nEND\n");
        assertEquals(1, modules.size());
        return modules.getFirst();
    }

    private List<String> messages() {
        return diagnostics.all().stream().map(Diagnostic::message).toList();
    }

    @Test
    void parsesImports() {
        Ast.Module m = parseOne("""
                IMPORTS
                    MODULE-IDENTITY, OBJECT-TYPE, Integer32 FROM SNMPv2-SMI
                    DisplayString FROM SNMPv2-TC;
                """);
        assertEquals(2, m.imports().size());
        assertEquals("SNMPv2-SMI", m.imports().get(0).module());
        assertEquals(List.of("MODULE-IDENTITY", "OBJECT-TYPE", "Integer32"), m.imports().get(0).symbols());
        assertEquals(List.of("DisplayString"), m.imports().get(1).symbols());
        assertEquals(List.of(), diagnostics.all());
    }

    @Test
    void toleratesMissingSemicolonAfterImports() {
        Ast.Module m = parseOne("""
                IMPORTS enterprises FROM SNMPv2-SMI
                test OBJECT IDENTIFIER ::= { enterprises 9999 }
                """);
        assertEquals(1, m.imports().size());
        assertEquals(1, m.definitions().size());
        assertFalse(diagnostics.hasErrors());
        assertEquals(List.of("Missing ';' at end of IMPORTS"), messages());
    }

    @Test
    void parsesObjectType() {
        Ast.Module m = parseOne("""
                testEntry OBJECT-TYPE
                    SYNTAX      TestEntry
                    MAX-ACCESS  not-accessible
                    STATUS      current
                    DESCRIPTION "An entry."
                    INDEX       { testIndex, IMPLIED testName }
                    ::= { testTable 1 }
                testName OBJECT-TYPE
                    SYNTAX      DisplayString (SIZE (0..32))
                    UNITS       "characters"
                    MAX-ACCESS  read-create
                    STATUS      current
                    DESCRIPTION "A name."
                    REFERENCE   "RFC 0000"
                    DEFVAL      { "default" }
                    ::= { testEntry 2 }
                """);
        Ast.ObjectType entry = assertInstanceOf(Ast.ObjectType.class, m.definitions().get(0));
        assertEquals(List.of(new Ast.IndexItem("testIndex", false), new Ast.IndexItem("testName", true)), entry.index());
        assertEquals(Optional.of("not-accessible"), entry.access());

        Ast.ObjectType name = assertInstanceOf(Ast.ObjectType.class, m.definitions().get(1));
        Ast.Constrained syntax = assertInstanceOf(Ast.Constrained.class, name.syntax().orElseThrow());
        assertTrue(syntax.size());
        assertEquals(Optional.of("characters"), name.units());
        assertEquals(Optional.of("\"default\""), name.defval());
        assertEquals(Optional.of("RFC 0000"), name.reference());
        assertEquals(2, name.oid().components().size());
        assertEquals(List.of(), diagnostics.all());
    }

    @Test
    void parsesTextualConventionAndSequence() {
        Ast.Module m = parseOne("""
                TestStatus ::= TEXTUAL-CONVENTION
                    DISPLAY-HINT "d"
                    STATUS       current
                    DESCRIPTION  "Status."
                    SYNTAX       INTEGER { up(1), down(2), unknown(-1) }
                TestEntry ::= SEQUENCE {
                    testIndex  Integer32,
                    testName   OCTET STRING (SIZE (0..32 | 64))
                }
                """);
        Ast.TextualConvention tc = assertInstanceOf(Ast.TextualConvention.class, m.definitions().get(0));
        assertEquals(Optional.of("d"), tc.displayHint());
        Ast.Enumerated e = assertInstanceOf(Ast.Enumerated.class, tc.syntax().orElseThrow());
        assertEquals(3, e.values().size());
        assertEquals(-1, e.values().get(2).number().intValue());

        Ast.TypeAssignment ta = assertInstanceOf(Ast.TypeAssignment.class, m.definitions().get(1));
        Ast.Sequence s = assertInstanceOf(Ast.Sequence.class, ta.type());
        assertEquals(2, s.elements().size());
        Ast.Constrained c = assertInstanceOf(Ast.Constrained.class, s.elements().get(1).type());
        assertEquals(2, c.ranges().size());
        assertEquals(List.of(), diagnostics.all());
    }

    @Test
    void parsesModuleIdentityWithRevisions() {
        Ast.Module m = parseOne("""
                testMIB MODULE-IDENTITY
                    LAST-UPDATED "202601010000Z"
                    ORGANIZATION "Org"
                    CONTACT-INFO "Contact"
                    DESCRIPTION  "Module."
                    REVISION     "202601010000Z"
                    DESCRIPTION  "Second."
                    REVISION     "202501010000Z"
                    DESCRIPTION  "First."
                    ::= { enterprises 9999 }
                """);
        Ast.ModuleIdentity mi = assertInstanceOf(Ast.ModuleIdentity.class, m.definitions().getFirst());
        assertEquals(Optional.of("202601010000Z"), mi.lastUpdated());
        assertEquals(Optional.of("Module."), mi.description());
        assertEquals(2, mi.revisions().size());
        assertEquals(Optional.of("First."), mi.revisions().get(1).description());
    }

    @Test
    void skipsComplianceModuleSections() {
        Ast.Module m = parseOne("""
                testCompliance MODULE-COMPLIANCE
                    STATUS      current
                    DESCRIPTION "Compliance."
                    MODULE -- this module
                        MANDATORY-GROUPS { testGroup }
                        GROUP testOptionalGroup
                        DESCRIPTION "Optional."
                        OBJECT testName
                        SYNTAX OBJECT IDENTIFIER
                        MIN-ACCESS read-only
                        DESCRIPTION "Read-only is enough."
                    ::= { testCompliances 1 }
                next OBJECT IDENTIFIER ::= { test 2 }
                """);
        Ast.Conformance c = assertInstanceOf(Ast.Conformance.class, m.definitions().get(0));
        assertEquals(Optional.of("Compliance."), c.description());
        assertEquals(2, m.definitions().size());
        assertEquals(List.of(), diagnostics.all());
    }

    @Test
    void parsesTrapType() {
        Ast.Module m = parseOne("""
                testTrap TRAP-TYPE
                    ENTERPRISE  testEnterprise
                    VARIABLES   { ifIndex }
                    DESCRIPTION "Trap."
                    ::= 3
                """);
        Ast.TrapType t = assertInstanceOf(Ast.TrapType.class, m.definitions().getFirst());
        assertEquals(Optional.of("testEnterprise"), t.enterprise());
        assertEquals(3, t.trapNumber());
    }

    @Test
    void skipsMacroDefinitions() {
        Ast.Module m = parseOne("""
                OBJECT-TYPE MACRO ::=
                BEGIN
                    TYPE NOTATION ::= "SYNTAX" Syntax "UNITS" ...
                    VALUE NOTATION ::= value(VALUE ObjectName)
                END
                test OBJECT IDENTIFIER ::= { iso 3 }
                """);
        assertInstanceOf(Ast.Macro.class, m.definitions().get(0));
        assertInstanceOf(Ast.ValueAssignment.class, m.definitions().get(1));
    }

    @Test
    void recoversAfterBrokenDefinition() {
        Ast.Module m = parseOne("""
                broken OBJECT-TYPE
                    SYNTAX  INTEGER {
                    STATUS  current
                    ::= { test 1 }
                good OBJECT IDENTIFIER ::= { test 2 }
                """);
        assertTrue(diagnostics.hasErrors());
        assertEquals(List.of("good"), m.definitions().stream().map(Ast.Definition::name).toList());
    }

    @Test
    void reportsMissingEnd() {
        List<Ast.Module> modules = parse("TEST-MIB DEFINITIONS ::= BEGIN\ntest OBJECT IDENTIFIER ::= { iso 3 }\n");
        assertEquals(1, modules.size());
        assertEquals(List.of("Missing END of module TEST-MIB"), messages());
    }

    @Test
    void parsesSeveralModulesInOneFile() {
        List<Ast.Module> modules = parse("""
                A-MIB DEFINITIONS ::= BEGIN
                a OBJECT IDENTIFIER ::= { iso 3 }
                END
                B-MIB DEFINITIONS ::= BEGIN
                b OBJECT IDENTIFIER ::= { iso 4 }
                END
                """);
        assertEquals(List.of("A-MIB", "B-MIB"), modules.stream().map(Ast.Module::name).toList());
    }

    @Test
    void ignoresTextBeforeModule() {
        List<Ast.Module> modules = parse("""
                Some header text copied from an RFC.

                A-MIB DEFINITIONS ::= BEGIN
                a OBJECT IDENTIFIER ::= { iso 3 }
                END
                """);
        assertEquals(1, modules.size());
        assertEquals(List.of(), diagnostics.all());
    }

    @Test
    void warnsAboutKeywordAndNameCase() {
        Ast.Module m = parseOne("""
                TestObject Object-Type
                    SYNTAX Integer32
                    MAX-ACCESS read-only
                    STATUS current
                    DESCRIPTION "x"
                    ::= { test 1 }
                """);
        assertEquals(1, m.definitions().size());
        assertFalse(diagnostics.hasErrors());
        assertEquals(2, diagnostics.all().size());
    }

    @Test
    void reportsMissingSyntax() {
        parseOne("""
                test OBJECT-TYPE
                    MAX-ACCESS read-only
                    STATUS current
                    DESCRIPTION "x"
                    ::= { test 1 }
                """);
        assertEquals(List.of("OBJECT-TYPE test has no SYNTAX clause"), messages());
    }
}
