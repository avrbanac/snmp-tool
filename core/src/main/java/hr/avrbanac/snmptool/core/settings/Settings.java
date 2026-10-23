package hr.avrbanac.snmptool.core.settings;

import java.util.List;
import java.util.Optional;

/**
 * User settings kept in {@code settings.json}.
 *
 * @param devices        device profiles, in the user's order
 * @param selectedDevice name of the device selected when the settings were saved
 */
public record Settings(List<SavedDevice> devices, Optional<String> selectedDevice) {

    public Settings {
        devices = List.copyOf(devices);
    }

    /** Settings with no devices. */
    public static Settings empty() {
        return new Settings(List.of(), Optional.empty());
    }

    public Settings withDevices(List<SavedDevice> devices, Optional<String> selectedDevice) {
        return new Settings(devices, selectedDevice);
    }
}
