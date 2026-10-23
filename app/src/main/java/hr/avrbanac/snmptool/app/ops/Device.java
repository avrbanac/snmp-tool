package hr.avrbanac.snmptool.app.ops;

import hr.avrbanac.snmptool.core.settings.SavedDevice;
import hr.avrbanac.snmptool.core.snmp.DeviceProfile;
import hr.avrbanac.snmptool.core.snmp.SnmpVersion;

/**
 * A device in the operations panel.
 *
 * @param profile         connection settings
 * @param rememberSecrets whether communities and passphrases are saved in the settings file
 * @param secretsKnown    whether the profile holds its secrets; false for devices loaded without them, until the
 *                        user enters them
 */
record Device(DeviceProfile profile, boolean rememberSecrets, boolean secretsKnown) {

    /** A device read from the settings; its secrets are known if they were saved or it needs none. */
    static Device loaded(SavedDevice saved) {
        return new Device(saved.profile(), saved.rememberSecrets(),
                saved.rememberSecrets() || !needsSecrets(saved.profile()));
    }

    /** Whether requests with the profile need a community or passphrase. */
    static boolean needsSecrets(DeviceProfile profile) {
        return profile.version() == SnmpVersion.V2C || profile.v3().level().authenticated();
    }

    String name() {
        return profile.name();
    }

    SavedDevice saved() {
        return new SavedDevice(profile, rememberSecrets);
    }

    /** Shown in the device chooser; never includes communities or passphrases. */
    @Override
    public String toString() {
        return profile.toString();
    }
}
