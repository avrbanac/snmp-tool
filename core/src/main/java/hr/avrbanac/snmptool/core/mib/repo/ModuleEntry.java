package hr.avrbanac.snmptool.core.mib.repo;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A module available in the repository.
 *
 * @param name             module name
 * @param origin           where the module comes from
 * @param overridesBundled whether an imported module replaces a bundled one with the same name
 * @param source           source name used in diagnostics
 * @param revision         latest revision date as {@code YYYYMMDDHHMM}, if the module has one
 * @param dependencies     modules it imports from
 * @param file             stored file of an imported module
 * @param importedFrom     original location of an imported module
 * @param importedAt       when an imported module was imported
 */
public record ModuleEntry(
        String name,
        Origin origin,
        boolean overridesBundled,
        String source,
        Optional<String> revision,
        List<String> dependencies,
        Optional<Path> file,
        Optional<String> importedFrom,
        Optional<Instant> importedAt) {

    public ModuleEntry {
        dependencies = List.copyOf(dependencies);
    }

    /** Where a module comes from. */
    public enum Origin {
        /** Shipped with the tool; cannot be removed. */
        BUNDLED,
        /** Imported by the user into the local store. */
        IMPORTED
    }
}
