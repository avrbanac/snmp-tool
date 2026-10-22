package hr.avrbanac.snmptool.core.snmp;

import java.util.ArrayList;
import java.util.List;

/**
 * How to reach one SNMP agent.
 *
 * @param name           display name
 * @param host           host name or IP address
 * @param port           UDP port, normally 161
 * @param version        SNMP version
 * @param timeoutMillis  timeout per request attempt
 * @param retries        retries after a timeout
 * @param maxRepetitions GETBULK max-repetitions used by walks
 * @param readCommunity  v2c community for get, getNext and walk
 * @param writeCommunity v2c community for set
 * @param v3             v3 security settings (used when {@code version} is {@link SnmpVersion#V3})
 */
public record DeviceProfile(
        String name,
        String host,
        int port,
        SnmpVersion version,
        int timeoutMillis,
        int retries,
        int maxRepetitions,
        String readCommunity,
        String writeCommunity,
        V3Security v3) {

    public static final int DEFAULT_PORT = 161;

    /** A v2c profile with the usual defaults. */
    public static DeviceProfile v2c(String name, String host, String readCommunity, String writeCommunity) {
        return new DeviceProfile(name, host, DEFAULT_PORT, SnmpVersion.V2C, 2000, 1, 25, readCommunity, writeCommunity,
                V3Security.noAuth(""));
    }

    /** Problems that prevent using the profile; empty if it is usable. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (host.isBlank()) {
            problems.add("Host is required");
        }
        if (port < 1 || port > 65535) {
            problems.add("Port must be between 1 and 65535");
        }
        if (timeoutMillis < 1) {
            problems.add("Timeout must be positive");
        }
        if (retries < 0) {
            problems.add("Retries must not be negative");
        }
        if (maxRepetitions < 1) {
            problems.add("Max repetitions must be positive");
        }
        if (version == SnmpVersion.V3) {
            if (v3.user().isBlank()) {
                problems.add("User is required for SNMPv3");
            }
            if (v3.level().authenticated() && v3.authPassword().length() < 8) {
                problems.add("Authentication passphrase must have at least 8 characters");
            }
            if (v3.level().encrypted() && v3.privPassword().length() < 8) {
                problems.add("Privacy passphrase must have at least 8 characters");
            }
            try {
                v3.contextEngineIdBytes();
            } catch (IllegalArgumentException e) {
                problems.add("Context engine ID must be hexadecimal");
            }
        }
        return problems;
    }

    /** E.g. {@code core-switch (10.0.0.1:161, v2c)}. Never includes communities or passphrases. */
    @Override
    public String toString() {
        return name + " (" + host + ":" + port + ", " + version + ")";
    }
}
