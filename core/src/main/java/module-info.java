/**
 * Core of the SNMP tool: MIB parsing, the resolved MIB model, the MIB repository and the SNMP client.
 * Has no JavaFX dependency.
 */
module hr.avrbanac.snmptool.core {
    exports hr.avrbanac.snmptool.core.mib;
    exports hr.avrbanac.snmptool.core.mib.model;
    exports hr.avrbanac.snmptool.core.mib.repo;
}
