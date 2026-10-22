package hr.avrbanac.snmptool.core.mib.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Immutable numeric object identifier, such as {@code 1.3.6.1.2.1}.
 *
 * <p>Each arc (sub-identifier) is an unsigned 32-bit value, stored as a {@code long}.
 * Ordering is lexicographic by arc, which matches SNMP getNext/walk order.
 */
public final class Oid implements Comparable<Oid> {

    /** Largest value an arc may have (2^32 - 1). */
    public static final long MAX_ARC = 0xFFFF_FFFFL;

    private final long[] arcs;

    private Oid(long[] arcs) {
        this.arcs = arcs;
    }

    /**
     * Creates an OID from its arcs.
     *
     * @throws IllegalArgumentException if there are no arcs or an arc is out of range
     */
    public static Oid of(long... arcs) {
        if (arcs.length == 0) {
            throw new IllegalArgumentException("OID must have at least one arc");
        }
        for (long arc : arcs) {
            checkArc(arc);
        }
        return new Oid(arcs.clone());
    }

    /**
     * Parses dotted notation, with or without a leading dot: {@code 1.3.6.1} or {@code .1.3.6.1}.
     *
     * @throws IllegalArgumentException if the text is not a valid numeric OID
     */
    public static Oid parse(String text) {
        String s = text.strip();
        if (s.startsWith(".")) {
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Empty OID: '" + text + "'");
        }
        String[] parts = s.split("\\.", -1);
        long[] arcs = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || !part.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new IllegalArgumentException("Invalid OID: '" + text + "'");
            }
            try {
                arcs[i] = Long.parseLong(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid OID: '" + text + "'", e);
            }
            if (arcs[i] > MAX_ARC) {
                throw new IllegalArgumentException("Arc out of range in OID: '" + text + "'");
            }
        }
        return new Oid(arcs);
    }

    private static void checkArc(long arc) {
        if (arc < 0 || arc > MAX_ARC) {
            throw new IllegalArgumentException("Arc out of range: " + arc);
        }
    }

    /** Number of arcs. */
    public int size() {
        return arcs.length;
    }

    /** Arc at the given position (0-based). */
    public long arc(int index) {
        return arcs[index];
    }

    /** Copy of all arcs. */
    public long[] arcs() {
        return arcs.clone();
    }

    /** Last arc. */
    public long lastArc() {
        return arcs[arcs.length - 1];
    }

    /** This OID extended by one arc. */
    public Oid child(long arc) {
        checkArc(arc);
        long[] extended = Arrays.copyOf(arcs, arcs.length + 1);
        extended[arcs.length] = arc;
        return new Oid(extended);
    }

    /** This OID without its last arc, or empty for a single-arc OID. */
    public Optional<Oid> parent() {
        return arcs.length == 1 ? Optional.empty() : Optional.of(new Oid(Arrays.copyOf(arcs, arcs.length - 1)));
    }

    /** Whether {@code prefix} is equal to, or an ancestor of, this OID. */
    public boolean startsWith(Oid prefix) {
        return prefix.arcs.length <= arcs.length
                && Arrays.equals(arcs, 0, prefix.arcs.length, prefix.arcs, 0, prefix.arcs.length);
    }

    @Override
    public int compareTo(Oid other) {
        return Arrays.compare(arcs, other.arcs);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Oid other && Arrays.equals(arcs, other.arcs);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(arcs);
    }

    /** Dotted notation without a leading dot, e.g. {@code 1.3.6.1}. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arcs.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(arcs[i]);
        }
        return sb.toString();
    }
}
