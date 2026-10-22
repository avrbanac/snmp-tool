package hr.avrbanac.snmptool.core.mib.resolve;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the module an IMPORTS clause means when it names a module that is not loaded. Vendor MIBs often
 * misspell module names, e.g. {@code RFC1212} for {@code RFC-1212} or {@code RFC-1213} for
 * {@code RFC1213-MIB}.
 */
public final class ModuleNames {

    /** Misspellings that a case- and punctuation-insensitive comparison does not catch. */
    private static final Map<String, String> ALIASES = Map.of(
            "RFC1213", "RFC1213-MIB",
            "RFC-1213", "RFC1213-MIB",
            "RFC-1213-MIB", "RFC1213-MIB",
            "RFC1158-MIB", "RFC1213-MIB",
            "RFC1155", "RFC1155-SMI",
            "RFC-1155", "RFC1155-SMI",
            "RFC-1155-SMI", "RFC1155-SMI");

    private ModuleNames() {
    }

    /**
     * The available module meant by {@code name}: the name itself, a known alias, or the only available module
     * whose name is equal ignoring case, {@code -} and {@code _}.
     */
    public static Optional<String> resolve(String name, Set<String> available) {
        if (available.contains(name)) {
            return Optional.of(name);
        }
        String alias = ALIASES.get(name.toUpperCase(Locale.ROOT));
        if (alias != null && available.contains(alias)) {
            return Optional.of(alias);
        }
        String key = normalize(name);
        Optional<String> match = Optional.empty();
        for (String candidate : available) {
            if (normalize(candidate).equals(key)) {
                if (match.isPresent()) {
                    return Optional.empty(); // ambiguous
                }
                match = Optional.of(candidate);
            }
        }
        return match;
    }

    private static String normalize(String name) {
        return name.toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
    }
}
