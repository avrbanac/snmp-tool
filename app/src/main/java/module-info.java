/**
 * JavaFX user interface of the SNMP tool.
 */
module hr.avrbanac.snmptool.app {
    requires hr.avrbanac.snmptool.core;
    requires javafx.controls;

    exports hr.avrbanac.snmptool.app to javafx.graphics;
}
