package hr.avrbanac.snmptool.core.mib;

import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.model.Oid;
import hr.avrbanac.snmptool.core.mib.model.OidTree;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 *
 * <p>An instance indexes one model, so that each search is a scan over prepared strings. Thread-safe.
 */
public final class MibSearch {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final MibModel model;
    /** Defined nodes in OID order; the arrays below are parallel to it. */
    private final List<OidTree.Node> nodes;
    /** Lower-case names of each node's definitions, joined with newlines. */
    private final String[] names;
    /** Normalized descriptions, built on the first description search. */
    private String[] descriptions;

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

    /** Builds the name index for a model; reuse the instance for all searches on that model. */
    public MibSearch(MibModel model) {
        this.model = model;
        this.nodes = model.tree().stream().filter(n -> !n.definitions().isEmpty()).toList();
        this.names = nodes.stream()
                .map(n -> n.definitions().stream().map(d -> d.name().toLowerCase(Locale.ROOT))
                        .collect(Collectors.joining("\n")))
                .toArray(String[]::new);
    }

    /**
     * Searches the model.
     *
     * @param query        search text; blank gives no results
     * @param descriptions whether to also match description text
     * @param limit        maximum number of nodes returned
     */
    public Result search(String query, boolean descriptions, int limit) {
        String q = query.strip();
        if (q.isEmpty()) {
            return new Result(List.of(), false);
        }
        Optional<OidTree.Node> exact = exactMatch(q);
        if (exact.isPresent()) {
            return new Result(List.of(exact.get()), false);
        }
        String needle = normalize(q);
        String[] texts = descriptions ? descriptions() : null;
        List<OidTree.Node> matches = new ArrayList<>();
        for (int i = 0; i < nodes.size() && matches.size() <= limit; i++) {
            if (names[i].contains(needle) || (texts != null && texts[i].contains(needle))) {
                matches.add(nodes.get(i));
            }
        }
        return matches.size() > limit
                ? new Result(matches.subList(0, limit), true)
                : new Result(matches, false);
    }

    /**
     * Builds the description index now instead of on the first description search; meant to be called from a
     * background thread.
     */
    public void indexDescriptions() {
        descriptions();
    }

    private synchronized String[] descriptions() {
        if (descriptions == null) {
            descriptions = nodes.stream()
                    .map(n -> n.definitions().stream()
                            .map(d -> d.description().map(MibSearch::normalize).orElse(""))
                            .collect(Collectors.joining("\n")))
                    .toArray(String[]::new);
        }
        return descriptions;
    }

    private Optional<OidTree.Node> exactMatch(String q) {
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

    /** Lower case with runs of whitespace collapsed, so phrases match across line breaks. */
    private static String normalize(String text) {
        return WHITESPACE.matcher(text.strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }
}
