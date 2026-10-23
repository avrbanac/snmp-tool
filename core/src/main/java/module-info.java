/**
 * Core of the SNMP tool: MIB parsing, the resolved MIB model, the MIB repository, the SNMP client and the
 * user settings.
 * Has no JavaFX dependency; SNMP4J is used internally and not exposed.
 */
module hr.avrbanac.snmptool.core {
    requires org.snmp4j;

    exports hr.avrbanac.snmptool.core.mib;
    exports hr.avrbanac.snmptool.core.mib.model;
    exports hr.avrbanac.snmptool.core.mib.repo;
    exports hr.avrbanac.snmptool.core.settings;
    exports hr.avrbanac.snmptool.core.snmp;
}
