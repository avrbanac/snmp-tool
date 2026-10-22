package hr.avrbanac.snmptool.core.snmp;

import hr.avrbanac.snmptool.core.mib.model.Oid;

/**
 * One variable binding: an instance OID and its value.
 *
 * @param oid   instance OID, e.g. {@code 1.3.6.1.2.1.1.1.0}
 * @param value value
 */
public record VarBindResult(Oid oid, SnmpValue value) {
}
