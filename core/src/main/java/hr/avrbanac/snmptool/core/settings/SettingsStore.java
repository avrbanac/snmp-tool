package hr.avrbanac.snmptool.core.settings;

import hr.avrbanac.snmptool.core.snmp.DeviceProfile;
import hr.avrbanac.snmptool.core.snmp.SnmpVersion;
import hr.avrbanac.snmptool.core.snmp.V3Security;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads and writes the user settings file, by default {@code ~/.snmp-tool/settings.json}.
 *
 * <p>Communities and passphrases are written only for devices with {@link SavedDevice#rememberSecrets()} on.
 * The file is replaced atomically and, where the file system supports POSIX permissions, readable and
 * writable by the owner only.
 *
 * <p>Loading is lenient: missing or invalid values fall back to defaults and unusable entries are skipped,
 * each reported as a problem. A file that is not valid JSON is moved aside to {@code settings.json.broken}
 * so that the next save does not overwrite it.
 */
public final class SettingsStore {

    /** Version of the file format, written as {@code "version"}. */
    static final int FORMAT_VERSION = 1;

    private final Path file;

    /** Settings read from the file and the problems found in it. */
    public record Loaded(Settings settings, List<String> problems, boolean existed) {

        public Loaded {
            problems = List.copyOf(problems);
        }
    }

    public SettingsStore(Path file) {
        this.file = file;
    }

    /** {@code ~/.snmp-tool/settings.json}. */
    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".snmp-tool", "settings.json");
    }

    public Path file() {
        return file;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Loading

    /**
     * Reads the settings; a missing file gives empty settings.
     *
     * @throws IOException if the file exists but cannot be read (or a malformed file cannot be moved aside)
     */
    public synchronized Loaded load() throws IOException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return new Loaded(Settings.empty(), List.of(), false);
        }
        Object root;
        try {
            root = Json.parse(text);
        } catch (Json.SyntaxException e) {
            Path broken = file.resolveSibling(file.getFileName() + ".broken");
            Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
            return new Loaded(Settings.empty(), List.of(file.getFileName() + " is not valid JSON (" + e.getMessage()
                    + "); it was moved to " + broken.getFileName() + " and the settings start empty"), true);
        }
        List<String> problems = new ArrayList<>();
        if (!(root instanceof Map<?, ?> map)) {
            problems.add(file.getFileName() + " does not contain a JSON object; the settings start empty");
            return new Loaded(Settings.empty(), problems, true);
        }
        Fields fields = new Fields(map, "settings", problems);
        List<SavedDevice> devices = new ArrayList<>();
        Set<String> names = new HashSet<>();
        List<?> entries = fields.list("devices");
        for (int i = 0; i < entries.size(); i++) {
            if (!(entries.get(i) instanceof Map<?, ?> entry)) {
                problems.add("Device " + (i + 1) + " is not an object; skipped");
                continue;
            }
            readDevice(new Fields(entry, "device " + (i + 1), problems)).ifPresent(d -> {
                if (names.add(d.profile().name())) {
                    devices.add(d);
                } else {
                    problems.add("Device " + d.profile().name() + " appears more than once; only the first is kept");
                }
            });
        }
        Optional<String> selected = fields.optionalString("selectedDevice").filter(names::contains);
        return new Loaded(new Settings(devices, selected), problems, true);
    }

    private static Optional<SavedDevice> readDevice(Fields f) {
        String name = f.string("name", "").strip();
        if (name.isEmpty()) {
            f.problems.add(capitalize(f.context) + " has no name; skipped");
            return Optional.empty();
        }
        Fields d = new Fields(f.map, "device " + name, f.problems);
        DeviceProfile defaults = DeviceProfile.v2c(name, "", "", "");
        String host = d.string("host", defaults.host());
        int port = d.integer("port", defaults.port());
        SnmpVersion version = d.enumValue("snmpVersion", SnmpVersion.class, defaults.version());
        int timeout = d.integer("timeoutMillis", defaults.timeoutMillis());
        int retries = d.integer("retries", defaults.retries());
        int maxRepetitions = d.integer("maxRepetitions", defaults.maxRepetitions());
        boolean remember = d.bool("rememberSecrets", false);
        String readCommunity = remember ? d.string("readCommunity", "") : "";
        String writeCommunity = remember ? d.string("writeCommunity", "") : "";
        Fields v3 = d.object("v3");
        V3Security v3Defaults = defaults.v3();
        V3Security security = new V3Security(
                v3.string("user", v3Defaults.user()),
                v3.enumValue("level", V3Security.Level.class, v3Defaults.level()),
                v3.enumValue("authProtocol", V3Security.AuthProtocol.class, v3Defaults.authProtocol()),
                remember ? v3.string("authPassphrase", "") : "",
                v3.enumValue("privProtocol", V3Security.PrivProtocol.class, v3Defaults.privProtocol()),
                remember ? v3.string("privPassphrase", "") : "",
                v3.string("contextName", v3Defaults.contextName()),
                v3.string("contextEngineId", v3Defaults.contextEngineId()));
        DeviceProfile profile = new DeviceProfile(name, host, port, version, timeout, retries, maxRepetitions,
                readCommunity, writeCommunity, security);
        return Optional.of(new SavedDevice(profile, remember));
    }

    /** Typed access to the members of a JSON object; wrong types are reported and replaced by the default. */
    private record Fields(Map<?, ?> map, String context, List<String> problems) {

        String string(String key, String defaultValue) {
            return optionalString(key).orElse(defaultValue);
        }

        Optional<String> optionalString(String key) {
            return switch (map.get(key)) {
                case null -> Optional.empty();
                case String s -> Optional.of(s);
                default -> {
                    wrongType(key, "a string");
                    yield Optional.empty();
                }
            };
        }

        int integer(String key, int defaultValue) {
            return switch (map.get(key)) {
                case null -> defaultValue;
                case Long l when l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE -> l.intValue();
                default -> {
                    wrongType(key, "an integer");
                    yield defaultValue;
                }
            };
        }

        boolean bool(String key, boolean defaultValue) {
            return switch (map.get(key)) {
                case null -> defaultValue;
                case Boolean b -> b;
                default -> {
                    wrongType(key, "true or false");
                    yield defaultValue;
                }
            };
        }

        <E extends Enum<E>> E enumValue(String key, Class<E> type, E defaultValue) {
            Optional<String> text = optionalString(key);
            if (text.isEmpty()) {
                return defaultValue;
            }
            try {
                return Enum.valueOf(type, text.get());
            } catch (IllegalArgumentException e) {
                problems.add(capitalize(context) + ": unknown " + key + " \"" + text.get() + "\"; using "
                        + defaultValue.name());
                return defaultValue;
            }
        }

        List<?> list(String key) {
            return switch (map.get(key)) {
                case null -> List.of();
                case List<?> l -> l;
                default -> {
                    wrongType(key, "an array");
                    yield List.of();
                }
            };
        }

        Fields object(String key) {
            return switch (map.get(key)) {
                case null -> new Fields(Map.of(), context, problems);
                case Map<?, ?> m -> new Fields(m, context, problems);
                default -> {
                    wrongType(key, "an object");
                    yield new Fields(Map.of(), context, problems);
                }
            };
        }

        private void wrongType(String key, String expected) {
            problems.add(capitalize(context) + ": " + key + " must be " + expected + "; using the default");
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Saving

    /** Writes the settings, replacing the file atomically. */
    public synchronized void save(Settings settings) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", (long) FORMAT_VERSION);
        settings.selectedDevice().ifPresent(s -> root.put("selectedDevice", s));
        root.put("devices", settings.devices().stream().map(SettingsStore::deviceJson).toList());

        Path directory = file.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temp = posix()
                ? Files.createTempFile(directory, file.getFileName().toString(), ".tmp", ownerOnly())
                : Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, Json.write(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Map<String, Object> deviceJson(SavedDevice device) {
        DeviceProfile p = device.profile();
        boolean remember = device.rememberSecrets();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name());
        m.put("host", p.host());
        m.put("port", (long) p.port());
        m.put("snmpVersion", p.version().name());
        m.put("timeoutMillis", (long) p.timeoutMillis());
        m.put("retries", (long) p.retries());
        m.put("maxRepetitions", (long) p.maxRepetitions());
        m.put("rememberSecrets", remember);
        if (remember) {
            m.put("readCommunity", p.readCommunity());
            m.put("writeCommunity", p.writeCommunity());
        }
        V3Security v3 = p.v3();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("user", v3.user());
        s.put("level", v3.level().name());
        s.put("authProtocol", v3.authProtocol().name());
        if (remember) {
            s.put("authPassphrase", v3.authPassword());
        }
        s.put("privProtocol", v3.privProtocol().name());
        if (remember) {
            s.put("privPassphrase", v3.privPassword());
        }
        s.put("contextName", v3.contextName());
        s.put("contextEngineId", v3.contextEngineId());
        m.put("v3", s);
        return m;
    }

    private boolean posix() {
        return file.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static FileAttribute<?> ownerOnly() {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    }
}
