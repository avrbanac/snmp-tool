package hr.avrbanac.snmptool.core.mib.repo;

import hr.avrbanac.snmptool.core.mib.BundledMibs;
import hr.avrbanac.snmptool.core.mib.MibLoader;
import hr.avrbanac.snmptool.core.mib.MibSource;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.repo.ImportPlan.Candidate;
import hr.avrbanac.snmptool.core.mib.repo.ImportPlan.Change;
import hr.avrbanac.snmptool.core.mib.repo.ImportPlan.Problem;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The MIB modules available to the tool: the bundled ones plus those the user imported into a local store.
 *
 * <p>The store is a directory with one {@code <MODULE>.mib} file per module and an {@code index.properties}
 * file recording where and when each module was imported. Everything else (module names, imports,
 * revisions) is read from the files themselves. An imported module with the same name as a bundled one
 * replaces it; removing the imported module brings the bundled one back.
 *
 * <p>All methods are synchronized; the store is meant to be used by one application instance at a time.
 */
public final class MibRepository {

    /** Files larger than this are not considered MIB files. */
    static final long MAX_FILE_SIZE = 16L * 1024 * 1024;

    private static final String INDEX = "index.properties";
    private static final String EXTENSION = ".mib";

    private final Path directory;
    private final List<MibSource> bundled;
    private List<BundledModule> bundledModules;

    /** A bundled module and the source it is in. */
    private record BundledModule(ModuleHeader header, MibSource source) {
    }

    /** An imported module and the file it is stored in. */
    private record StoredModule(ModuleHeader header, Path file) {
    }

    /** Headers of a stored file, valid while its size and modification time are unchanged. */
    private record CachedFile(long size, FileTime modified, List<ModuleHeader> headers) {
    }

    /** Parsed headers of stored files, so that listing modules does not parse every file again. */
    private final Map<Path, CachedFile> cache = new HashMap<>();

    /**
     * Installed modules by name (imported ones replacing bundled ones) and the names of the imported ones.
     */
    private record Installed(Map<String, ModuleHeader> headers, Set<String> imported) {
    }

    /** Repository with the bundled IETF MIBs and a store in the given directory. */
    public MibRepository(Path directory) {
        this(directory, BundledMibs.ietf());
    }

    /** Repository with the given bundled sources; mainly for tests. */
    public MibRepository(Path directory, List<MibSource> bundled) {
        this.directory = directory;
        this.bundled = List.copyOf(bundled);
    }

    /** {@code ~/.snmp-tool/mibs}. */
    public static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home"), ".snmp-tool", "mibs");
    }

    public Path directory() {
        return directory;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reading

    /** All available modules (bundled ones that are not overridden, and imported ones), sorted by name. */
    public synchronized List<ModuleEntry> modules() throws IOException {
        Properties index = readIndex();
        List<StoredModule> stored = storedModules();
        Set<String> storedNames = storedNames(stored);
        Set<String> bundledNames = new HashSet<>();
        List<ModuleEntry> entries = new ArrayList<>();
        for (BundledModule b : bundledModules()) {
            bundledNames.add(b.header().name());
            if (!storedNames.contains(b.header().name())) {
                entries.add(new ModuleEntry(b.header().name(), ModuleEntry.Origin.BUNDLED, false, b.source().name(),
                        b.header().revision(), b.header().dependencies(), Optional.empty(), Optional.empty(),
                        Optional.empty()));
            }
        }
        for (StoredModule s : stored) {
            String name = s.header().name();
            entries.add(new ModuleEntry(name, ModuleEntry.Origin.IMPORTED, bundledNames.contains(name),
                    s.file().getFileName().toString(), s.header().revision(), s.header().dependencies(), Optional.of(s.file()),
                    Optional.ofNullable(index.getProperty(name + ".from")),
                    Optional.ofNullable(index.getProperty(name + ".at")).flatMap(MibRepository::parseInstant)));
        }
        entries.sort(Comparator.comparing(ModuleEntry::name));
        return entries;
    }

    /** Sources to load: bundled ones (except those replaced by imported modules) followed by imported ones. */
    public synchronized List<MibSource> sources() throws IOException {
        List<StoredModule> stored = storedModules();
        Set<String> storedNames = storedNames(stored);
        List<MibSource> sources = new ArrayList<>();
        Set<MibSource> added = new HashSet<>();
        for (BundledModule b : bundledModules()) {
            if (!storedNames.contains(b.header().name()) && added.add(b.source())) {
                sources.add(b.source());
            }
        }
        for (Path file : stored.stream().map(StoredModule::file).distinct().toList()) {
            sources.add(MibSource.read(file));
        }
        return sources;
    }

    /** Parses and resolves all available modules. */
    public MibModel load() throws IOException {
        return MibLoader.load(sources());
    }

    /** Names of the modules that import from the given module. */
    public synchronized List<String> dependants(String module) throws IOException {
        return modules().stream()
                .filter(e -> e.dependencies().contains(module))
                .map(ModuleEntry::name)
                .sorted()
                .toList();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Import

    /**
     * Works out what importing the given files and directories (searched recursively) would do. Nothing is
     * written.
     */
    public synchronized ImportPlan planImport(List<Path> paths) throws IOException {
        List<Problem> problems = new ArrayList<>();
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        Installed installed = installed();

        for (Path file : expand(paths, problems)) {
            List<ModuleHeader> headers;
            try {
                if (Files.size(file) > MAX_FILE_SIZE) {
                    problems.add(new Problem(file, "File is too large to be a MIB"));
                    continue;
                }
                headers = ModuleHeader.read(MibSource.read(file));
            } catch (IOException e) {
                problems.add(new Problem(file, "Cannot read file: " + e.getMessage()));
                continue;
            }
            if (headers.isEmpty()) {
                problems.add(new Problem(file, "No MIB module found"));
                continue;
            }
            for (ModuleHeader h : headers) {
                Candidate candidate = candidate(file, h, installed);
                Candidate previous = candidates.get(h.name());
                if (previous == null) {
                    candidates.put(h.name(), candidate);
                } else if (isNewer(candidate.revision(), previous.revision())) {
                    candidates.put(h.name(), candidate);
                    problems.add(new Problem(previous.file(), h.name() + " is also in " + file
                            + " with a newer revision; this copy is skipped"));
                } else {
                    problems.add(new Problem(file, h.name() + " is also in " + previous.file()
                            + "; this copy is skipped"));
                }
            }
        }
        return new ImportPlan(new ArrayList<>(candidates.values()), problems, installed.headers().keySet());
    }

    private static Candidate candidate(Path file, ModuleHeader h, Installed installed) {
        ModuleHeader current = installed.headers().get(h.name());
        Change change;
        if (current == null) {
            change = Change.NEW;
        } else if (current.digest().equals(h.digest())) {
            change = Change.IDENTICAL;
        } else if (h.revision().isPresent() && current.revision().isPresent()
                && !h.revision().equals(current.revision())) {
            change = isNewer(h.revision(), current.revision()) ? Change.NEWER : Change.OLDER;
        } else {
            change = Change.CHANGED;
        }
        boolean replacesBundled = current != null && !installed.imported().contains(h.name());
        return new Candidate(h.name(), file, h.text(), change, replacesBundled, h.revision(),
                Optional.ofNullable(current).flatMap(ModuleHeader::revision), h.dependencies(), h.errors(),
                h.warnings());
    }

    private Installed installed() throws IOException {
        Map<String, ModuleHeader> headers = new LinkedHashMap<>();
        bundledModules().forEach(b -> headers.put(b.header().name(), b.header()));
        List<StoredModule> stored = storedModules();
        stored.forEach(s -> headers.put(s.header().name(), s.header()));
        return new Installed(headers, storedNames(stored));
    }

    /**
     * Imports the selected candidates of a plan: writes their files into the store and records where they
     * came from. Candidates that are identical to the installed module are skipped.
     *
     * @return names of the modules written
     */
    public synchronized List<String> applyImport(ImportPlan plan, Set<String> selected) throws IOException {
        Files.createDirectories(directory);
        Properties index = readIndex();
        Map<String, Path> storedFiles = new LinkedHashMap<>();
        storedModules().forEach(s -> storedFiles.put(s.header().name(), s.file()));
        String now = Instant.now().toString();
        List<String> written = new ArrayList<>();
        for (Candidate c : plan.candidates()) {
            if (!selected.contains(c.module()) || !c.importable()) {
                continue;
            }
            Path target = storedFiles.getOrDefault(c.module(), directory.resolve(fileName(c.module())));
            writeAtomically(target, c.text());
            cache.remove(target);
            index.setProperty(c.module() + ".from", c.file().toAbsolutePath().toString());
            index.setProperty(c.module() + ".at", now);
            written.add(c.module());
        }
        writeIndex(index);
        return written;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Remove

    /**
     * Removes an imported module from the store. If it replaced a bundled module, the bundled one becomes
     * available again.
     *
     * @throws IllegalArgumentException if the module is not an imported one
     */
    public synchronized void remove(String module) throws IOException {
        StoredModule stored = storedModules().stream()
                .filter(s -> s.header().name().equals(module))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(module + " is not an imported module"));
        Files.delete(stored.file());
        cache.remove(stored.file());
        Properties index = readIndex();
        index.remove(module + ".from");
        index.remove(module + ".at");
        writeIndex(index);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Store access

    private List<BundledModule> bundledModules() {
        if (bundledModules == null) {
            List<BundledModule> modules = new ArrayList<>();
            for (MibSource source : bundled) {
                ModuleHeader.read(source).forEach(h -> modules.add(new BundledModule(h, source)));
            }
            bundledModules = List.copyOf(modules);
        }
        return bundledModules;
    }

    /** Modules in the store, by file name. Files without a module are still loaded so their errors show. */
    private List<StoredModule> storedModules() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(EXTENSION) && Files.isRegularFile(p))
                    .sorted()
                    .toList();
        }
        cache.keySet().retainAll(files);
        List<StoredModule> modules = new ArrayList<>();
        for (Path file : files) {
            headers(file).forEach(h -> modules.add(new StoredModule(h, file)));
        }
        return modules;
    }

    /** Headers of a stored file, from the cache when the file is unchanged. */
    private List<ModuleHeader> headers(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        CachedFile cached = cache.get(file);
        if (cached != null && cached.size() == attributes.size()
                && cached.modified().equals(attributes.lastModifiedTime())) {
            return cached.headers();
        }
        MibSource source = MibSource.read(file);
        List<ModuleHeader> headers = ModuleHeader.read(source).stream().map(ModuleHeader::withoutText).toList();
        if (headers.isEmpty()) {
            String name = file.getFileName().toString();
            headers = List.of(new ModuleHeader(name.substring(0, name.length() - EXTENSION.length()), List.of(),
                    Optional.empty(), "", ModuleHeader.digest(source.text()), List.of()));
        }
        cache.put(file, new CachedFile(attributes.size(), attributes.lastModifiedTime(), headers));
        return headers;
    }

    private static List<Path> expand(List<Path> paths, List<Problem> problems) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path path : paths) {
            if (Files.isDirectory(path)) {
                try (Stream<Path> stream = Files.walk(path)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> !p.getFileName().toString().startsWith("."))
                            .sorted()
                            .forEach(files::add);
                }
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            } else {
                problems.add(new Problem(path, "Not a file or directory"));
            }
        }
        return files;
    }

    private Properties readIndex() throws IOException {
        Properties index = new Properties();
        Path file = directory.resolve(INDEX);
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                index.load(reader);
            }
        }
        return index;
    }

    private void writeIndex(Properties index) throws IOException {
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, INDEX, ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            index.store(writer, "SNMP Tool: origin of imported MIB modules (<module>.from, <module>.at)");
        }
        move(temp, directory.resolve(INDEX));
    }

    private void writeAtomically(Path target, String text) throws IOException {
        Path temp = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        move(temp, target);
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** File name for a module; characters that are unsafe in file names are replaced. */
    static String fileName(String module) {
        return module.replaceAll("[^A-Za-z0-9_.-]", "_") + EXTENSION;
    }

    private static boolean isNewer(Optional<String> revision, Optional<String> than) {
        return revision.isPresent() && (than.isEmpty() || revision.get().compareTo(than.get()) > 0);
    }

    private static Set<String> storedNames(List<StoredModule> stored) {
        Set<String> names = new HashSet<>();
        stored.forEach(m -> names.add(m.header().name()));
        return names;
    }

    private static Optional<Instant> parseInstant(String text) {
        try {
            return Optional.of(Instant.parse(text));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
