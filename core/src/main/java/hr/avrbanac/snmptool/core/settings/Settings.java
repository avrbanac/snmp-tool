package hr.avrbanac.snmptool.core.settings;

import hr.avrbanac.snmptool.core.mib.model.Oid;
import java.util.List;
import java.util.Optional;

/**
 * User settings kept in {@code settings.json}.
 *
 * @param devices        device profiles, most recently used first
 * @param selectedDevice name of the device selected when the settings were saved
 * @param window         main window layout; empty until the window was closed once
 * @param selectedOid    OID of the node selected in the MIB tree
 */
public record Settings(List<SavedDevice> devices, Optional<String> selectedDevice, Optional<WindowLayout> window,
        Optional<Oid> selectedOid) {

    public Settings {
        devices = List.copyOf(devices);
    }

    /** Settings with no devices and no saved layout. */
    public static Settings empty() {
        return new Settings(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public Settings withDevices(List<SavedDevice> devices, Optional<String> selectedDevice) {
        return new Settings(devices, selectedDevice, window, selectedOid);
    }

    public Settings withWindow(Optional<WindowLayout> window, Optional<Oid> selectedOid) {
        return new Settings(devices, selectedDevice, window, selectedOid);
    }
}
