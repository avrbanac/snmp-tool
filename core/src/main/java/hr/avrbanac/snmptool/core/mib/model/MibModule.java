package hr.avrbanac.snmptool.core.mib.model;

import java.util.List;
import java.util.Optional;

/**
 * A resolved MIB module.
 *
 * @param name         module name
 * @param source       name of the source the module was read from
 * @param smiVersion   SMI version the module is written in
 * @param lastUpdated  LAST-UPDATED of the MODULE-IDENTITY
 * @param organization ORGANIZATION of the MODULE-IDENTITY
 * @param contactInfo  CONTACT-INFO of the MODULE-IDENTITY
 * @param description  DESCRIPTION of the MODULE-IDENTITY
 * @param revisions    REVISION clauses, in source order (normally newest first)
 * @param imports      IMPORTS, in source order
 * @param nodes        definitions with an object identifier, in source order
 * @param types        named types (textual conventions and type assignments), in source order
 */
public record MibModule(
        String name,
        String source,
        SmiVersion smiVersion,
        Optional<String> lastUpdated,
        Optional<String> organization,
        Optional<String> contactInfo,
        Optional<String> description,
        List<Revision> revisions,
        List<Import> imports,
        List<MibNode> nodes,
        List<MibType> types) {

    public MibModule {
        revisions = List.copyOf(revisions);
        imports = List.copyOf(imports);
        nodes = List.copyOf(nodes);
        types = List.copyOf(types);
    }

    /** SMI version of a module. */
    public enum SmiVersion {
        V1,
        V2
    }

    /** {@code REVISION "date" DESCRIPTION "text"}. */
    public record Revision(String date, Optional<String> description) {
    }

    /** {@code symbols FROM module}. */
    public record Import(String module, List<String> symbols) {
        public Import {
            symbols = List.copyOf(symbols);
        }
    }

    /** Names of the modules this module imports from, without duplicates, in source order. */
    public List<String> dependencies() {
        return imports.stream().map(Import::module).distinct().toList();
    }
}
