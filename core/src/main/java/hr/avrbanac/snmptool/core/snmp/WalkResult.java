package hr.avrbanac.snmptool.core.snmp;

/**
 * How a walk ended.
 *
 * @param count number of variable bindings delivered
 * @param end   why the walk stopped
 */
public record WalkResult(int count, End end) {

    /** Why a walk stopped. */
    public enum End {
        /** The next OID was outside the walked subtree. */
        END_OF_SUBTREE,
        /** The agent reported endOfMibView. */
        END_OF_MIB_VIEW,
        /** The subtree was empty, so the root itself was fetched with a get. */
        SINGLE_GET,
        /** Stopped by the caller. */
        CANCELLED
    }
}
