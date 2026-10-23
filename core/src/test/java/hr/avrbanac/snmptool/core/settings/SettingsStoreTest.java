package hr.avrbanac.snmptool.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hr.avrbanac.snmptool.core.mib.model.Oid;
import hr.avrbanac.snmptool.core.snmp.DeviceProfile;
import hr.avrbanac.snmptool.core.snmp.SnmpVersion;
import hr.avrbanac.snmptool.core.snmp.V3Security;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsStoreTest {

    @TempDir
    Path dir;

    private static final DeviceProfile V2C = new DeviceProfile("switch", "10.0.0.1", 1161, SnmpVersion.V2C, 3000, 2,
            50, "pub-secret", "priv-secret", V3Security.noAuth(""));
    private static final DeviceProfile V3 = new DeviceProfile("router", "router.example", 161, SnmpVersion.V3, 2000,
            1, 25, "", "", new V3Security("admin", V3Security.Level.AUTH_PRIV, V3Security.AuthProtocol.SHA512,
                    "auth-secret", V3Security.PrivProtocol.AES256, "priv-pass", "ctx", "80001f88"));

    private SettingsStore store() {
        return new SettingsStore(dir.resolve("sub").resolve("settings.json"));
    }

    @Test
    void missingFileGivesEmptySettings() throws IOException {
        SettingsStore.Loaded loaded = store().load();
        assertEquals(Settings.empty(), loaded.settings());
        assertTrue(loaded.problems().isEmpty());
        assertFalse(loaded.existed());
    }

    @Test
    void roundTripKeepsSecretsOnlyWhenRemembered() throws IOException {
        SettingsStore store = store();
        store.save(Settings.empty().withDevices(List.of(new SavedDevice(V2C, true), new SavedDevice(V3, false)),
                Optional.of("router")));

        String text = Files.readString(store.file());
        assertTrue(text.contains("pub-secret"), text);
        assertFalse(text.contains("auth-secret") || text.contains("priv-pass"), text);

        SettingsStore.Loaded loaded = store.load();
        assertTrue(loaded.problems().isEmpty(), loaded.problems().toString());
        assertTrue(loaded.existed());
        Settings settings = loaded.settings();
        assertEquals(Optional.of("router"), settings.selectedDevice());
        assertEquals(List.of(new SavedDevice(V2C, true), new SavedDevice(new DeviceProfile("router", "router.example",
                161, SnmpVersion.V3, 2000, 1, 25, "", "", new V3Security("admin", V3Security.Level.AUTH_PRIV,
                        V3Security.AuthProtocol.SHA512, "", V3Security.PrivProtocol.AES256, "", "ctx", "80001f88")),
                false)), settings.devices());
    }

    @Test
    void forgettingSecretsRemovesThemFromTheFile() throws IOException {
        SettingsStore store = store();
        store.save(Settings.empty().withDevices(List.of(new SavedDevice(V3, true)), Optional.empty()));
        assertTrue(Files.readString(store.file()).contains("auth-secret"));
        store.save(Settings.empty().withDevices(List.of(new SavedDevice(V3, false)), Optional.empty()));
        assertFalse(Files.readString(store.file()).contains("auth-secret"));
    }

    @Test
    void fileIsReadableByOwnerOnly() throws IOException {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        SettingsStore store = store();
        store.save(Settings.empty().withDevices(List.of(new SavedDevice(V2C, true)), Optional.empty()));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file())));
        try (var files = Files.list(store.file().getParent())) {
            assertEquals(List.of(store.file()), files.toList(), "no temporary files left behind");
        }
    }

    @Test
    void malformedFileIsMovedAside() throws IOException {
        SettingsStore store = store();
        Files.createDirectories(store.file().getParent());
        Files.writeString(store.file(), "{ \"devices\": [ ");
        SettingsStore.Loaded loaded = store.load();
        assertEquals(Settings.empty(), loaded.settings());
        assertEquals(1, loaded.problems().size());
        assertTrue(loaded.problems().getFirst().contains("settings.json.broken"), loaded.problems().toString());
        assertFalse(Files.exists(store.file()));
        assertEquals("{ \"devices\": [ ", Files.readString(store.file().resolveSibling("settings.json.broken")));
    }

    @Test
    void invalidValuesFallBackToDefaultsAndAreReported() throws IOException {
        SettingsStore store = store();
        Files.createDirectories(store.file().getParent());
        Files.writeString(store.file(), """
                {
                  "version": 1,
                  "selectedDevice": "gone",
                  "futureSetting": { "x": 1 },
                  "devices": [
                    { "name": "a", "host": "h", "port": "161", "snmpVersion": "V1", "rememberSecrets": true,
                      "readCommunity": "r", "v3": { "level": "AUTH_PRIV", "authProtocol": "SHA0" } },
                    { "host": "no-name" },
                    42,
                    { "name": "a", "host": "duplicate" }
                  ]
                }
                """);
        SettingsStore.Loaded loaded = store.load();
        Settings settings = loaded.settings();
        assertEquals(1, settings.devices().size());
        DeviceProfile a = settings.devices().getFirst().profile();
        assertEquals("h", a.host());
        assertEquals(DeviceProfile.DEFAULT_PORT, a.port());
        assertEquals(SnmpVersion.V2C, a.version());
        assertEquals("r", a.readCommunity());
        assertEquals(V3Security.Level.AUTH_PRIV, a.v3().level());
        assertEquals(V3Security.AuthProtocol.SHA256, a.v3().authProtocol());
        assertEquals(Optional.empty(), settings.selectedDevice());
        assertEquals(List.of(
                "Device a: port must be an integer; using the default",
                "Device a: unknown snmpVersion \"V1\"; using V2C",
                "Device a: unknown authProtocol \"SHA0\"; using SHA256",
                "Device 2 has no name; skipped",
                "Device 3 is not an object; skipped",
                "Device a appears more than once; only the first is kept"), loaded.problems());
    }

    @Test
    void windowLayoutAndSelectedOidRoundTrip() throws IOException {
        SettingsStore store = store();
        WindowLayout layout = new WindowLayout(-1200, 40, 1400.5, 900, true, 0.3, 0.65,
                Map.of("Name", 300.0, "Value", 412.25));
        Settings settings = Settings.empty().withDevices(List.of(new SavedDevice(V2C, false)), Optional.of("switch"))
                .withWindow(Optional.of(layout), Optional.of(Oid.parse("1.3.6.1.2.1.2.2.1.2")));
        store.save(settings);
        SettingsStore.Loaded loaded = store.load();
        assertTrue(loaded.problems().isEmpty(), loaded.problems().toString());
        assertEquals(Optional.of(layout), loaded.settings().window());
        assertEquals(Optional.of(Oid.parse("1.3.6.1.2.1.2.2.1.2")), loaded.settings().selectedOid());
    }

    @Test
    void missingOrInvalidLayoutValuesAreUnknown() throws IOException {
        SettingsStore store = store();
        Files.createDirectories(store.file().getParent());
        Files.writeString(store.file(), """
                { "window": { "width": 1000, "height": "tall", "columnWidths": { "Name": 200, "Raw": -5, "OID": "x" } },
                  "selectedOid": "sysDescr" }
                """);
        SettingsStore.Loaded loaded = store.load();
        WindowLayout w = loaded.settings().window().orElseThrow();
        assertEquals(1000, w.width());
        assertTrue(Double.isNaN(w.x()) && Double.isNaN(w.height()) && Double.isNaN(w.treeDivider()));
        assertFalse(w.maximized());
        assertEquals(Map.of("Name", 200.0), w.columnWidths());
        assertEquals(Optional.empty(), loaded.settings().selectedOid());
        assertEquals(List.of(
                "Window: height must be a number; using the default",
                "Window: OID must be a number; using the default",
                "Settings: selectedOid \"sysDescr\" is not a numeric OID; ignored"), loaded.problems());

        Files.writeString(store.file(), "{ \"window\": [] }");
        assertEquals(Optional.empty(), store.load().settings().window());
    }

    @Test
    void savedDeviceToStringHasNoSecrets() {
        String text = new SavedDevice(V3, true).toString() + new SavedDevice(V2C, true);
        assertFalse(text.contains("secret") || text.contains("priv-pass"), text);
    }
}
