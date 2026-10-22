package hr.avrbanac.snmptool.core.mib.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hr.avrbanac.snmptool.core.mib.BundledMibs;
import hr.avrbanac.snmptool.core.mib.MibSource;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.model.Oid;
import hr.avrbanac.snmptool.core.mib.repo.ImportPlan.Candidate;
import hr.avrbanac.snmptool.core.mib.repo.ImportPlan.Change;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MibRepositoryTest {

    private static final List<MibSource> BUNDLED = BundledMibs.ietf();

    @TempDir
    Path store;

    @TempDir
    Path input;

    private MibRepository repository() {
        return new MibRepository(store, BUNDLED);
    }

    private static String vendorMib(String revision, String extra) {
        return """
                VENDOR-MIB DEFINITIONS ::= BEGIN
                IMPORTS MODULE-IDENTITY, OBJECT-TYPE, Integer32, enterprises FROM SNMPv2-SMI;

                vendorMIB MODULE-IDENTITY
                    LAST-UPDATED "%sZ"
                    ORGANIZATION "Vendor"
                    CONTACT-INFO "support@vendor.example"
                    DESCRIPTION  "Vendor MIB."
                    ::= { enterprises 4242 }

                vendorValue OBJECT-TYPE
                    SYNTAX      Integer32
                    MAX-ACCESS  read-only
                    STATUS      current
                    DESCRIPTION "A value.%s"
                    ::= { vendorMIB 1 }
                END
                """.formatted(revision, extra);
    }

    private Path write(String name, String text) throws IOException {
        Path file = input.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    private static Candidate only(ImportPlan plan) {
        assertEquals(1, plan.candidates().size(), plan.toString());
        return plan.candidates().getFirst();
    }

    private void importAll(MibRepository repository, Path... files) throws IOException {
        ImportPlan plan = repository.planImport(List.of(files));
        repository.applyImport(plan, Set.copyOf(plan.candidates().stream().map(Candidate::module).toList()));
    }

    @Test
    void emptyStoreHasOnlyBundledModules() throws IOException {
        MibRepository repository = repository();
        assertEquals(BUNDLED.size(), repository.sources().size());
        assertTrue(repository.modules().stream().allMatch(e -> e.origin() == ModuleEntry.Origin.BUNDLED));
        assertFalse(Files.exists(store.resolve("index.properties")));
    }

    @Test
    void importsNewModule() throws IOException {
        MibRepository repository = repository();
        Path file = write("vendor.my", vendorMib("202601010000", ""));
        ImportPlan plan = repository.planImport(List.of(file));
        Candidate c = only(plan);
        assertEquals("VENDOR-MIB", c.module());
        assertEquals(Change.NEW, c.change());
        assertTrue(c.selectedByDefault());
        assertEquals(Optional.of("202601010000"), c.revision());
        assertEquals(Map.of(), plan.missingDependencies(Set.of("VENDOR-MIB")));

        assertEquals(List.of("VENDOR-MIB"), repository.applyImport(plan, Set.of("VENDOR-MIB")));
        assertTrue(Files.isRegularFile(store.resolve("VENDOR-MIB.mib")));

        ModuleEntry entry = repository.modules().stream().filter(e -> e.name().equals("VENDOR-MIB")).findFirst()
                .orElseThrow();
        assertEquals(ModuleEntry.Origin.IMPORTED, entry.origin());
        assertEquals(Optional.of(file.toAbsolutePath().toString()), entry.importedFrom());
        assertTrue(entry.importedAt().isPresent());

        MibModel model = repository.load();
        assertEquals(List.of(), model.diagnostics());
        assertEquals(Oid.parse("1.3.6.1.4.1.4242.1"), model.lookup("VENDOR-MIB::vendorValue").orElseThrow().oid());
    }

    @Test
    void classifiesReimports() throws IOException {
        MibRepository repository = repository();
        importAll(repository, write("vendor.my", vendorMib("202601010000", "")));

        assertEquals(Change.IDENTICAL, only(repository.planImport(List.of(input.resolve("vendor.my")))).change());
        assertFalse(only(repository.planImport(List.of(input.resolve("vendor.my")))).importable());

        Candidate newer = only(repository.planImport(List.of(write("newer.my", vendorMib("202602010000", "")))));
        assertEquals(Change.NEWER, newer.change());
        assertEquals(Optional.of("202601010000"), newer.installedRevision());
        assertTrue(newer.selectedByDefault());

        Candidate older = only(repository.planImport(List.of(write("older.my", vendorMib("202501010000", "")))));
        assertEquals(Change.OLDER, older.change());
        assertFalse(older.selectedByDefault());

        Candidate changed = only(repository.planImport(List.of(write("edited.my", vendorMib("202601010000", " Edited.")))));
        assertEquals(Change.CHANGED, changed.change());
    }

    @Test
    void updatingReplacesTheStoredFile() throws IOException {
        MibRepository repository = repository();
        importAll(repository, write("vendor.my", vendorMib("202601010000", "")));
        importAll(repository, write("newer.my", vendorMib("202602010000", " Newer.")));
        assertEquals(1, repository.modules().stream().filter(e -> e.name().equals("VENDOR-MIB")).count());
        assertTrue(Files.readString(store.resolve("VENDOR-MIB.mib")).contains("Newer."));
    }

    @Test
    void importedModuleOverridesBundledOneUntilRemoved() throws IOException {
        MibRepository repository = repository();
        String bundledIfMib = BUNDLED.stream().filter(s -> s.name().equals("IF-MIB.mib")).findFirst().orElseThrow().text();
        String patched = bundledIfMib.replace("LAST-UPDATED \"200006140000Z\"", "LAST-UPDATED \"203001010000Z\"");
        Candidate c = only(repository.planImport(List.of(write("IF-MIB.txt", patched))));
        assertEquals(Change.NEWER, c.change());
        assertTrue(c.replacesBundled());

        importAll(repository, input.resolve("IF-MIB.txt"));
        ModuleEntry ifMib = repository.modules().stream().filter(e -> e.name().equals("IF-MIB")).findFirst()
                .orElseThrow();
        assertEquals(ModuleEntry.Origin.IMPORTED, ifMib.origin());
        assertTrue(ifMib.overridesBundled());
        assertEquals(BUNDLED.size(), repository.sources().size());
        assertEquals(List.of(), repository.load().diagnostics());

        repository.remove("IF-MIB");
        assertEquals(ModuleEntry.Origin.BUNDLED, repository.modules().stream()
                .filter(e -> e.name().equals("IF-MIB")).findFirst().orElseThrow().origin());
        assertFalse(Files.exists(store.resolve("IF-MIB.mib")));
    }

    @Test
    void reportsMissingDependenciesForSelection() throws IOException {
        MibRepository repository = repository();
        Path tc = write("tc.my", """
                VENDOR-TC DEFINITIONS ::= BEGIN
                IMPORTS TEXTUAL-CONVENTION FROM SNMPv2-TC;
                VendorName ::= TEXTUAL-CONVENTION
                    STATUS current
                    DESCRIPTION "Name."
                    SYNTAX OCTET STRING (SIZE (0..32))
                END
                """);
        Path mib = write("objects.my", """
                VENDOR-OBJECTS DEFINITIONS ::= BEGIN
                IMPORTS enterprises, OBJECT-TYPE FROM SNMPv2-SMI
                        VendorName FROM VENDOR-TC
                        vendorThing FROM VENDOR-OTHER;
                vendorObjects OBJECT IDENTIFIER ::= { enterprises 4243 }
                END
                """);
        ImportPlan plan = repository.planImport(List.of(tc, mib));
        assertEquals(Map.of("VENDOR-OBJECTS", List.of("VENDOR-TC", "VENDOR-OTHER")),
                plan.missingDependencies(Set.of("VENDOR-OBJECTS")));
        assertEquals(Map.of("VENDOR-OBJECTS", List.of("VENDOR-OTHER")),
                plan.missingDependencies(Set.of("VENDOR-OBJECTS", "VENDOR-TC")));
    }

    @Test
    void misspelledModuleNamesAreNotMissing() throws IOException {
        Path file = write("v1.my", """
                V1-MIB DEFINITIONS ::= BEGIN
                IMPORTS enterprises FROM RFC1155-SMI
                        OBJECT-TYPE FROM RFC1212
                        DisplayString FROM RFC-1213;
                v1 OBJECT IDENTIFIER ::= { enterprises 779 }
                END
                """);
        ImportPlan plan = repository().planImport(List.of(file));
        assertEquals(Map.of(), plan.missingDependencies(Set.of("V1-MIB")));
    }

    @Test
    void picksUpChangesMadeToStoredFiles() throws IOException {
        MibRepository repository = repository();
        importAll(repository, write("vendor.my", vendorMib("202601010000", "")));
        assertEquals(Optional.of("202601010000"), repository.modules().stream()
                .filter(e -> e.name().equals("VENDOR-MIB")).findFirst().orElseThrow().revision());

        Files.writeString(store.resolve("VENDOR-MIB.mib"), vendorMib("202607010000", " Edited by hand."));
        assertEquals(Optional.of("202607010000"), repository.modules().stream()
                .filter(e -> e.name().equals("VENDOR-MIB")).findFirst().orElseThrow().revision());
        assertEquals(Change.IDENTICAL, only(repository.planImport(List.of(write("again.my",
                vendorMib("202607010000", " Edited by hand."))))).change());
    }

    @Test
    void splitsFilesWithSeveralModules() throws IOException {
        MibRepository repository = repository();
        Path file = write("bundle.my", """
                -- Two modules in one file
                A-MIB DEFINITIONS ::= BEGIN
                IMPORTS enterprises FROM SNMPv2-SMI;
                a OBJECT IDENTIFIER ::= { enterprises 1001 }
                END

                B-MIB DEFINITIONS ::= BEGIN
                IMPORTS a FROM A-MIB;
                b OBJECT IDENTIFIER ::= { a 1 }
                END
                """);
        ImportPlan plan = repository.planImport(List.of(file));
        assertEquals(List.of("A-MIB", "B-MIB"), plan.candidates().stream().map(Candidate::module).toList());
        importAll(repository, file);

        String b = Files.readString(store.resolve("B-MIB.mib"));
        assertTrue(b.startsWith("B-MIB DEFINITIONS"));
        assertFalse(b.contains("A-MIB DEFINITIONS"));
        MibModel model = repository.load();
        assertEquals(List.of(), model.diagnostics());
        assertEquals(Oid.parse("1.3.6.1.4.1.1001.1"), model.lookup("B-MIB::b").orElseThrow().oid());
        assertEquals(List.of("B-MIB"), repository.dependants("A-MIB"));
    }

    @Test
    void scansFoldersAndReportsProblems() throws IOException {
        MibRepository repository = repository();
        write("mibs/vendor/VENDOR-MIB.txt", vendorMib("202601010000", ""));
        write("mibs/README.md", "These are vendor MIBs.");
        write("mibs/old/VENDOR-MIB-OLD.txt", vendorMib("202001010000", ""));
        ImportPlan plan = repository.planImport(List.of(input.resolve("mibs")));

        Candidate c = only(plan);
        assertEquals(Optional.of("202601010000"), c.revision());
        assertEquals(2, plan.problems().size());
        assertTrue(plan.problems().stream().anyMatch(p -> p.message().equals("No MIB module found")));
        assertTrue(plan.problems().stream().anyMatch(p -> p.message().contains("newer revision")));
    }

    @Test
    void countsParseProblemsPerCandidate() throws IOException {
        Path file = write("broken.my", """
                BROKEN-MIB DEFINITIONS ::= BEGIN
                IMPORTS enterprises FROM SNMPv2-SMI
                broken OBJECT IDENTIFIER ::= { enterprises 1 }
                bad OBJECT-TYPE SYNTAX INTEGER { ::= { broken 1 }
                END
                """);
        Candidate c = only(repository().planImport(List.of(file)));
        assertEquals(1, c.warnings());
        assertTrue(c.errors() > 0);
    }

    @Test
    void cannotRemoveBundledModules() {
        assertThrows(IllegalArgumentException.class, () -> repository().remove("IF-MIB"));
    }

    @Test
    void sanitizesFileNames() {
        assertEquals("VENDOR-MIB.mib", MibRepository.fileName("VENDOR-MIB"));
        assertEquals("ODD_NAME.mib", MibRepository.fileName("ODD/NAME"));
    }
}
