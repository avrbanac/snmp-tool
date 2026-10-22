package hr.avrbanac.snmptool.core.mib;

import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.model.MibNode;
import hr.avrbanac.snmptool.core.mib.model.Oid;
import hr.avrbanac.snmptool.core.mib.model.OidTree;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Finds tree nodes matching a search query.
 *
 * <p>Query forms:
 * <ul>
 *   <li>numeric OID ({@code 1.3.6.1.2.1.2.2.1.2.3}): the node itself or, for an instance OID, the deepest
 *       defined node above it;</li>
 *   <li>qualified name ({@code IF-MIB::ifDescr}): that definition;</li>
 *   <li>anything else: case-insensitive substring of the name, and optionally of the description (where
 *       line breaks and indentation count as single spaces).</li>
 * </ul>
 */
public final class MibSearch {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private MibSearch() {
    }

    /**
     * Search result.
     *
     * @param nodes     matching nodes in OID order
     * @param truncated whether more nodes matched than the limit
     */
    public record Result(List<OidTree.Node> nodes, boolean truncated) {
        public Result {
            nodes = List.copyOf(nodes);
        }
    }

    /**
     * Searches the model.
     *
     * @param query        search text; blank gives no results
     * @param descriptions whether to also match description text
     * @param limit        maximum number of nodes returned
     */
    public static Result search(MibModel model, String query, boolean descriptions, int limit) {
        String q = query.strip();
        if (q.isEmpty()) {
            return new Result(List.of(), false);
        }
        Optional<OidTree.Node> exact = exactMatch(model, q);
        if (exact.isPresent()) {
            return new Result(List.of(exact.get()), false);
        }
        String needle = normalize(q);
        List<OidTree.Node> matches = model.tree().stream()
                .filter(node -> node.definitions().stream().anyMatch(d -> matches(d, needle, descriptions)))
                .limit(limit + 1L)
                .toList();
        return matches.size() > limit
                ? new Result(matches.subList(0, limit), true)
                : new Result(matches, false);
    }

    private static Optional<OidTree.Node> exactMatch(MibModel model, String q) {
        if (q.contains("::")) {
            return model.lookup(q).flatMap(n -> model.tree().find(n.oid()));
        }
        if (Character.isDigit(q.charAt(0)) || q.charAt(0) == '.') {
            try {
                Oid oid = Oid.parse(q);
                return model.tree().find(oid).or(() -> model.tree().longestPrefix(oid));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static boolean matches(MibNode node, String needle, boolean descriptions) {
        return node.name().toLowerCase(Locale.ROOT).contains(needle)
                || (descriptions && node.description().map(d -> normalize(d).contains(needle)).orElse(false));
    }

    /** Lower case with runs of whitespace collapsed, so phrases match across line breaks. */
    private static String normalize(String text) {
        return WHITESPACE.matcher(text.strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }
}
