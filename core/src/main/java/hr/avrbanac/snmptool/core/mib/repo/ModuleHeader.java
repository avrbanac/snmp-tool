package hr.avrbanac.snmptool.core.mib.repo;

import hr.avrbanac.snmptool.core.mib.MibSource;
import hr.avrbanac.snmptool.core.mib.model.Diagnostic;
import hr.avrbanac.snmptool.core.mib.parse.Ast;
import hr.avrbanac.snmptool.core.mib.parse.Diagnostics;
import hr.avrbanac.snmptool.core.mib.parse.Parser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * What the repository needs to know about a module without resolving it: name, dependencies, revision and
 * its own text.
 *
 * @param name         module name
 * @param dependencies modules it imports from
 * @param revision     latest of LAST-UPDATED and REVISION dates, normalized to {@code YYYYMMDDHHMM}
 * @param text         the module's source text (the whole file for single-module files); empty for headers
 *                     cached by the repository, which only need the digest
 * @param digest       SHA-256 of the text, used to detect identical modules
 * @param diagnostics  parser diagnostics that belong to this module
 */
record ModuleHeader(String name, List<String> dependencies, Optional<String> revision, String text, String digest,
        List<Diagnostic> diagnostics) {

    ModuleHeader {
        dependencies = List.copyOf(dependencies);
        diagnostics = List.copyOf(diagnostics);
    }

    /** Parses a source and returns one header per module, in source order. */
    static List<ModuleHeader> read(MibSource source) {
        String text = source.text().replace("\r\n", "\n");
        Diagnostics diagnostics = new Diagnostics();
        List<Ast.Module> modules = Parser.parse(text, source.name(), diagnostics);
        List<ModuleHeader> headers = new ArrayList<>();
        for (int i = 0; i < modules.size(); i++) {
            Ast.Module m = modules.get(i);
            String moduleText = modules.size() == 1 ? text
                    : text.substring(i == 0 ? 0 : modules.get(i - 1).endOffset(), m.endOffset()).strip() + "\n";
            int firstLine = i == 0 ? 1 : m.position().line();
            int lastLine = i + 1 < modules.size() ? modules.get(i + 1).position().line() - 1 : Integer.MAX_VALUE;
            List<Diagnostic> own = diagnostics.all().stream()
                    .filter(d -> d.position().line() >= firstLine && d.position().line() <= lastLine)
                    .toList();
            headers.add(new ModuleHeader(m.name(), dependencies(m), revision(m), moduleText, digest(moduleText), own));
        }
        return headers;
    }

    /** Copy without the text, for keeping in memory. */
    ModuleHeader withoutText() {
        return new ModuleHeader(name, dependencies, revision, "", digest, diagnostics);
    }

    static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    long errors() {
        return diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).count();
    }

    long warnings() {
        return diagnostics.size() - errors();
    }

    private static List<String> dependencies(Ast.Module m) {
        return m.imports().stream().map(Ast.Import::module).distinct().toList();
    }

    private static Optional<String> revision(Ast.Module m) {
        return m.definitions().stream()
                .filter(Ast.ModuleIdentity.class::isInstance)
                .map(Ast.ModuleIdentity.class::cast)
                .findFirst()
                .flatMap(mi -> {
                    List<String> dates = new ArrayList<>();
                    mi.lastUpdated().ifPresent(dates::add);
                    mi.revisions().forEach(r -> dates.add(r.date()));
                    return dates.stream().map(ModuleHeader::normalizeDate).flatMap(Optional::stream)
                            .max(String::compareTo);
                });
    }

    /**
     * Normalizes an SMI date ({@code YYYYMMDDHHMMZ}, or {@code YYMMDDHHMMZ} meaning 19YY) to
     * {@code YYYYMMDDHHMM}, which sorts chronologically.
     */
    static Optional<String> normalizeDate(String date) {
        String d = date.strip();
        if (!d.matches("\\d{10}(\\d{2})?[Zz]")) {
            return Optional.empty();
        }
        return Optional.of((d.length() == 11 ? "19" : "") + d.substring(0, d.length() - 1));
    }
}
