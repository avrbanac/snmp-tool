package hr.avrbanac.snmptool.core.mib.repo;

import hr.avrbanac.snmptool.core.mib.resolve.ModuleNames;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What an import would do, worked out before anything is written. Created by
 * {@link MibRepository#planImport(List)} and carried out by {@link MibRepository#applyImport}.
 *
 * @param candidates modules found in the selected files, one per module name
 * @param problems   files or modules that cannot be imported
 * @param available  names of the modules already in the repository (bundled and imported)
 */
public record ImportPlan(List<Candidate> candidates, List<Problem> problems, Set<String> available) {

    public ImportPlan {
        candidates = List.copyOf(candidates);
        problems = List.copyOf(problems);
        available = Set.copyOf(available);
    }

    /** How a candidate relates to the module of the same name already in the repository. */
    public enum Change {
        /** No module with this name yet. */
        NEW,
        /** Newer revision than the installed module. */
        NEWER,
        /** Older revision than the installed module. */
        OLDER,
        /** Different content, but the same or no revision date, so it cannot be told which is newer. */
        CHANGED,
        /** Exactly the same text as the installed module; nothing to do. */
        IDENTICAL
    }

    /**
     * A module that could be imported.
     *
     * @param module            module name
     * @param file              file it was found in
     * @param text              module text that would be stored
     * @param change            relation to the installed module
     * @param replacesBundled   whether the installed module is a bundled one
     * @param revision          revision of the candidate ({@code YYYYMMDDHHMM})
     * @param installedRevision revision of the installed module
     * @param dependencies      modules it imports from
     * @param errors            number of parse errors
     * @param warnings          number of parse warnings
     */
    public record Candidate(
            String module,
            Path file,
            String text,
            Change change,
            boolean replacesBundled,
            Optional<String> revision,
            Optional<String> installedRevision,
            List<String> dependencies,
            long errors,
            long warnings) {

        public Candidate {
            dependencies = List.copyOf(dependencies);
        }

        /** Whether the import dialog should select this candidate initially. */
        public boolean selectedByDefault() {
            return change == Change.NEW || change == Change.NEWER || change == Change.CHANGED;
        }

        /** Whether importing it would change anything. */
        public boolean importable() {
            return change != Change.IDENTICAL;
        }
    }

    /**
     * A file or module that is skipped.
     *
     * @param file    the file
     * @param message why it is skipped
     */
    public record Problem(Path file, String message) {
    }

    /**
     * Modules that the selected candidates import from but that would still not be available after the
     * import, keyed by the candidate that needs them. Misspelled module names that the resolver would map to
     * an available module (see {@code ModuleNames}) do not count as missing.
     */
    public Map<String, List<String>> missingDependencies(Set<String> selected) {
        Set<String> after = new HashSet<>(available);
        after.addAll(selected);
        Map<String, List<String>> missing = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            if (!selected.contains(c.module())) {
                continue;
            }
            List<String> absent = new ArrayList<>(c.dependencies().stream()
                    .filter(d -> ModuleNames.resolve(d, after).isEmpty())
                    .toList());
            if (!absent.isEmpty()) {
                missing.put(c.module(), absent);
            }
        }
        return missing;
    }
}
