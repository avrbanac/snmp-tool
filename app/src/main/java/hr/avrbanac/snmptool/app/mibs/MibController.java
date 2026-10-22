package hr.avrbanac.snmptool.app.mibs;

import hr.avrbanac.snmptool.app.Background;
import hr.avrbanac.snmptool.core.mib.MibSearch;
import hr.avrbanac.snmptool.core.mib.model.Diagnostic;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.repo.ImportPlan;
import hr.avrbanac.snmptool.core.mib.repo.MibRepository;
import hr.avrbanac.snmptool.core.mib.repo.ModuleEntry;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Owns the MIB repository and the current model; runs loading, import and removal in the background and
 * publishes the results as properties for the UI.
 */
public final class MibController {

    private final MibRepository repository;
    private final ReadOnlyObjectWrapper<MibModel> model = new ReadOnlyObjectWrapper<>();
    private MibSearch search;
    private final ReadOnlyObjectWrapper<List<ModuleEntry>> modules = new ReadOnlyObjectWrapper<>(List.of());
    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper();
    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("Loading MIBs…");
    private Path lastDirectory;

    /** Model, its search index and the module list, loaded together. */
    private record Loaded(MibModel model, MibSearch search, List<ModuleEntry> modules) {
    }

    public MibController(MibRepository repository) {
        this.repository = repository;
    }

    /** The current model; {@code null} until the first load finishes. */
    public ReadOnlyObjectProperty<MibModel> modelProperty() {
        return model.getReadOnlyProperty();
    }

    /** Search index of the current model; set before {@link #modelProperty()} changes. */
    public MibSearch search() {
        return search;
    }

    /** Available modules, bundled and imported. */
    public ReadOnlyObjectProperty<List<ModuleEntry>> modulesProperty() {
        return modules.getReadOnlyProperty();
    }

    /** Whether loading, planning, importing or removing is in progress. */
    public ReadOnlyBooleanProperty busyProperty() {
        return busy.getReadOnlyProperty();
    }

    /** Status line text: the last action and a summary of the model. */
    public ReadOnlyStringProperty statusProperty() {
        return status.getReadOnlyProperty();
    }

    public Path storeDirectory() {
        return repository.directory();
    }

    /** Loads the repository; {@code done} prefixes the status line when not empty. */
    public void reload(String done) {
        busy.set(true);
        Background.run("mib-loader", () -> {
            MibModel loadedModel = repository.load();
            return new Loaded(loadedModel, new MibSearch(loadedModel), repository.modules());
        }, loaded -> {
            search = loaded.search();
            model.set(loaded.model());
            // Description search is used less often; prepare it after the tree is shown.
            Background.run("mib-search-index", () -> {
                loaded.search().indexDescriptions();
                return loaded.search();
            }, _ -> { }, _ -> { });
            modules.set(loaded.modules());
            busy.set(false);
            status.set((done.isEmpty() ? "" : done + " · ") + summary(loaded.model()));
        }, e -> fail(null, "Loading MIBs failed", e));
    }

    public void importFiles(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import MIB files");
        initialDirectory().ifPresent(chooser::setInitialDirectory);
        chooser.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter("MIB files", "*.mib", "*.my", "*.txt", "*.smi", "*.mi2", "*.asn",
                        "*.asn1", "*.MIB", "*.MY", "*.TXT"),
                new FileChooser.ExtensionFilter("All files", "*"));
        List<File> files = chooser.showOpenMultipleDialog(owner);
        if (files != null && !files.isEmpty()) {
            lastDirectory = files.getFirst().toPath().getParent();
            plan(files.stream().map(File::toPath).toList(), owner);
        }
    }

    public void importFolder(Window owner) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Import MIB folder (searched recursively)");
        initialDirectory().ifPresent(chooser::setInitialDirectory);
        File folder = chooser.showDialog(owner);
        if (folder != null) {
            lastDirectory = folder.toPath();
            plan(List.of(folder.toPath()), owner);
        }
    }

    /** Asks for confirmation and removes an imported module. */
    public void remove(ModuleEntry entry, Window owner) {
        if (entry.origin() != ModuleEntry.Origin.IMPORTED) {
            return;
        }
        List<String> dependants = modules.get().stream()
                .filter(e -> e.dependencies().contains(entry.name()))
                .map(ModuleEntry::name)
                .sorted()
                .toList();
        StringBuilder text = new StringBuilder();
        if (entry.overridesBundled()) {
            text.append("The bundled version of ").append(entry.name()).append(" will be used again.\n\n");
        }
        if (!dependants.isEmpty()) {
            text.append("These modules import from it and will lose definitions that depend on it:\n")
                    .append(String.join(", ", dependants));
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, text.toString().strip(), ButtonType.OK,
                ButtonType.CANCEL);
        confirm.initOwner(owner);
        confirm.setTitle("Remove MIB module");
        confirm.setHeaderText("Remove " + entry.name() + "?");
        if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) {
            return;
        }
        busy.set(true);
        Background.run("mib-remove", () -> {
            repository.remove(entry.name());
            return entry.name();
        }, name -> reload("Removed " + name), e -> fail(owner, "Removing " + entry.name() + " failed", e));
    }

    private void plan(List<Path> paths, Window owner) {
        busy.set(true);
        String previous = status.get();
        status.set("Reading MIB files…");
        Background.run("mib-import", () -> repository.planImport(paths), plan -> {
            busy.set(false);
            status.set(previous);
            ImportDialog.show(plan, owner).ifPresent(selected -> apply(plan, selected, owner));
        }, e -> fail(owner, "Reading MIB files failed", e));
    }

    private void apply(ImportPlan plan, Set<String> selected, Window owner) {
        busy.set(true);
        Background.run("mib-import", () -> repository.applyImport(plan, selected),
                written -> reload("Imported " + MibFormat.count(written.size(), "module")),
                e -> fail(owner, "Importing MIBs failed", e));
    }

    private Optional<File> initialDirectory() {
        return Optional.ofNullable(lastDirectory).filter(Files::isDirectory).map(Path::toFile);
    }

    private void fail(Window owner, String what, Throwable e) {
        busy.set(false);
        status.set(what + ": " + e.getMessage());
        Alert alert = new Alert(Alert.AlertType.ERROR, String.valueOf(e.getMessage()));
        alert.initOwner(owner);
        alert.setHeaderText(what);
        alert.show();
    }

    static String summary(MibModel model) {
        return String.format("%d modules · %,d nodes", model.modules().size(), model.tree().size());
    }

    /** E.g. {@code 2 errors, 5 warnings} or {@code no problems}. */
    public static String problems(List<Diagnostic> diagnostics) {
        long errors = diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).count();
        long warnings = diagnostics.size() - errors;
        if (errors == 0 && warnings == 0) {
            return "no problems";
        }
        return MibFormat.count(errors, "error") + ", " + MibFormat.count(warnings, "warning");
    }
}
