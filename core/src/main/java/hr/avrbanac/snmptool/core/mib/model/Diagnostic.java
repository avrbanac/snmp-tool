package hr.avrbanac.snmptool.core.mib.model;

/**
 * A problem found while parsing or resolving MIB modules.
 *
 * @param severity how serious the problem is
 * @param position where the problem was found
 * @param message  human-readable description
 */
public record Diagnostic(Severity severity, SourcePosition position, String message) {

    /** Severity of a diagnostic. */
    public enum Severity {
        /** The definition could not be loaded, or loaded with parts missing. */
        ERROR,
        /** The source violates the SMI rules, but was understood. */
        WARNING
    }

    @Override
    public String toString() {
        return severity + " " + position + ": " + message;
    }
}
