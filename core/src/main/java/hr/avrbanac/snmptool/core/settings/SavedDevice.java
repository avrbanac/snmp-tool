package hr.avrbanac.snmptool.core.settings;

import hr.avrbanac.snmptool.core.snmp.DeviceProfile;

/**
 * A device profile as kept in the settings.
 *
 * @param profile         the profile; without communities and passphrases when it was loaded with
 *                        {@code rememberSecrets} off
 * @param rememberSecrets whether communities and passphrases are written to the settings file
 */
public record SavedDevice(DeviceProfile profile, boolean rememberSecrets) {

    /** Never includes communities or passphrases. */
    @Override
    public String toString() {
        return "SavedDevice[" + profile + ", rememberSecrets=" + rememberSecrets + "]";
    }
}
