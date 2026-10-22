package hr.avrbanac.snmptool.core.snmp;

/**
 * SNMP versions supported by the tool.
 */
public enum SnmpVersion {
    V2C("v2c"),
    V3("v3");

    private final String text;

    SnmpVersion(String text) {
        this.text = text;
    }

    @Override
    public String toString() {
        return text;
    }
}
