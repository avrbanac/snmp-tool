package hr.avrbanac.snmptool.core.mib.model;

/**
 * What a {@link MibNode} defines.
 */
public enum NodeKind {
    /** {@code name OBJECT IDENTIFIER ::= ...}, or one of the built-in roots. */
    OBJECT_IDENTIFIER,
    MODULE_IDENTITY,
    OBJECT_IDENTITY,
    /** OBJECT-TYPE that is a scalar. */
    SCALAR,
    /** OBJECT-TYPE whose syntax is SEQUENCE OF. */
    TABLE,
    /** OBJECT-TYPE that is a conceptual row (has INDEX or AUGMENTS). */
    ROW,
    /** OBJECT-TYPE that is a column of a row. */
    COLUMN,
    /** SMIv2 NOTIFICATION-TYPE. */
    NOTIFICATION,
    /** SMIv1 TRAP-TYPE, placed at {@code enterprise.0.trapNumber}. */
    TRAP,
    OBJECT_GROUP,
    NOTIFICATION_GROUP,
    MODULE_COMPLIANCE,
    AGENT_CAPABILITIES
}
