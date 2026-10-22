package hr.avrbanac.snmptool.core.snmp;

/**
 * A request failed: no response, an error status, a v3 report or a local problem.
 */
public final class SnmpException extends Exception {

    private static final long serialVersionUID = 1L;

    /** What kind of failure. */
    public enum Kind {
        /** No response within timeout and retries. */
        TIMEOUT,
        /** The agent answered with a non-zero error-status. */
        ERROR_STATUS,
        /** The agent answered with a v3 report (unknown user, wrong digest, ...). */
        REPORT,
        /** Local problem: unknown host, socket error, invalid settings. */
        LOCAL
    }

    private final Kind kind;
    private final int errorStatus;
    private final int errorIndex;

    public SnmpException(Kind kind, String message) {
        this(kind, message, 0, 0, null);
    }

    public SnmpException(Kind kind, String message, Throwable cause) {
        this(kind, message, 0, 0, cause);
    }

    /** Error-status response; {@code errorIndex} is 1-based as in the PDU (0 if not variable-specific). */
    public static SnmpException errorStatus(int status, String statusText, int index, String detail) {
        return new SnmpException(Kind.ERROR_STATUS, statusText + (detail.isEmpty() ? "" : " (" + detail + ")"),
                status, index, null);
    }

    private SnmpException(Kind kind, String message, int errorStatus, int errorIndex, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.errorStatus = errorStatus;
        this.errorIndex = errorIndex;
    }

    public Kind kind() {
        return kind;
    }

    /** SNMP error-status for {@link Kind#ERROR_STATUS}, else 0. */
    public int errorStatus() {
        return errorStatus;
    }

    /** 1-based error-index for {@link Kind#ERROR_STATUS}, else 0. */
    public int errorIndex() {
        return errorIndex;
    }
}
