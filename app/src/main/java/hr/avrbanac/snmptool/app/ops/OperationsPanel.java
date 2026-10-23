package hr.avrbanac.snmptool.app.ops;

import hr.avrbanac.snmptool.app.Background;
import hr.avrbanac.snmptool.app.Clipboards;
import hr.avrbanac.snmptool.core.mib.model.BaseType;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.model.MibNode;
import hr.avrbanac.snmptool.core.mib.model.NodeKind;
import hr.avrbanac.snmptool.core.mib.model.Oid;
import hr.avrbanac.snmptool.core.mib.model.OidTree;
import hr.avrbanac.snmptool.core.settings.SavedDevice;
import hr.avrbanac.snmptool.core.snmp.DeviceProfile;
import hr.avrbanac.snmptool.core.snmp.ResultExport;
import hr.avrbanac.snmptool.core.snmp.ResultFormatter;
import hr.avrbanac.snmptool.core.snmp.SnmpException;
import hr.avrbanac.snmptool.core.snmp.SnmpSession;
import hr.avrbanac.snmptool.core.snmp.SnmpValue;
import hr.avrbanac.snmptool.core.snmp.ValueParser;
import hr.avrbanac.snmptool.core.snmp.VarBindResult;
import hr.avrbanac.snmptool.core.snmp.WalkResult;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * SNMP operations on the selected device: get, getNext, walk and set, with the results table.
 *
 * <p>Requests run in the background, one at a time. The session for the selected device is opened on first
 * use and reused until the device or its settings change. Devices whose secrets are not saved ask for them
 * on first use.
 */
public final class OperationsPanel {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObservableList<Device> devices = FXCollections.observableArrayList();
    private final ComboBox<Device> device = new ComboBox<>(devices);
    private final TextField oid = new TextField();
    private final Button get = new Button("Get");
    private final Button getNext = new Button("Get Next");
    private final Button walk = new Button("Walk");
    private final Button set = new Button("Set…");
    private final Button stop = new Button("Stop");
    private final Label status = new Label();
    private final TableView<ResultFormatter.Formatted> results = new TableView<>();
    private final VBox root;
    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper();
    private final AtomicBoolean cancel = new AtomicBoolean();
    private final Consumer<Oid> showInTree;
    private final Runnable devicesChanged;
    /** Set while devices are being replaced, so that the changes are not reported. */
    private boolean loadingDevices;

    private MibModel model;
    private ResultFormatter formatter;
    /** Device of the last request, named in exports; never includes secrets. */
    private String resultsDevice = "";
    private Path exportDirectory;
    private ResultExport.Format exportFormat = ResultExport.Format.CSV;
    /** Accessed from background threads; guarded by {@code this}. */
    private SnmpSession session;

    /**
     * @param showInTree     selects the definition of an OID in the MIB tree
     * @param devicesChanged called when devices are added, edited, removed or reordered, or another device is
     *                       selected
     */
    public OperationsPanel(Consumer<Oid> showInTree, Runnable devicesChanged) {
        this.showInTree = showInTree;
        this.devicesChanged = devicesChanged;
        device.setPrefWidth(260);
        device.valueProperty().addListener((_, _, _) -> {
            if (!loadingDevices) {
                closeSession();
                devicesChanged.run();
            }
        });
        Button newDevice = new Button("New…");
        newDevice.setOnAction(_ -> editDevice(Optional.empty()));
        Button editDevice = new Button("Edit…");
        editDevice.setOnAction(_ -> Optional.ofNullable(device.getValue()).ifPresent(d -> editDevice(Optional.of(d))));
        Button removeDevice = new Button("Remove");
        removeDevice.setOnAction(_ -> removeDevice());
        removeDevice.disableProperty().bind(busy.getReadOnlyProperty().or(device.valueProperty().isNull()));
        editDevice.disableProperty().bind(busy.getReadOnlyProperty().or(device.valueProperty().isNull()));

        oid.setPromptText("OID, name or name.instance, e.g. sysDescr.0, IF-MIB::ifDescr.3");
        oid.setOnAction(_ -> runGet());
        HBox.setHgrow(oid, Priority.ALWAYS);
        HBox deviceRow = new HBox(6, new Label("Device"), device, newDevice, editDevice, removeDevice,
                new Label("OID"), oid);
        deviceRow.setAlignment(Pos.CENTER_LEFT);

        get.setOnAction(_ -> runGet());
        getNext.setOnAction(_ -> runGetNext());
        walk.setOnAction(_ -> runWalk());
        set.setOnAction(_ -> parseOid().ifPresent(this::runSet));
        stop.setOnAction(_ -> cancel.set(true));
        get.setTooltip(new Tooltip("GET the instance in the OID field (Enter)"));
        getNext.setTooltip(new Tooltip("GETNEXT; the OID field moves to the result, so pressing again steps on"));
        walk.setTooltip(new Tooltip("Walk the subtree under the OID with GETBULK"));
        for (Button b : List.of(get, getNext, walk, set)) {
            b.disableProperty().bind(busy.getReadOnlyProperty().or(device.valueProperty().isNull()));
        }
        stop.setDisable(true);
        Button export = new Button("Export…");
        export.setTooltip(new Tooltip("Save the results as CSV or text"));
        export.setOnAction(_ -> export(false));
        export.disableProperty().bind(busy.getReadOnlyProperty().or(Bindings.isEmpty(results.getItems())));
        Button clear = new Button("Clear");
        clear.setOnAction(_ -> {
            results.getItems().clear();
            status.setText("");
        });
        status.getStyleClass().add("op-status");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox opRow = new HBox(6, get, getNext, walk, set, stop, status, spacer, export, clear);
        opRow.setAlignment(Pos.CENTER_LEFT);
        status.setMinWidth(0);
        HBox.setHgrow(status, Priority.SOMETIMES);

        createResultsTable();
        VBox.setVgrow(results, Priority.ALWAYS);
        root = new VBox(6, deviceRow, opRow, results);
        root.getStyleClass().add("pane");
    }

    public Parent root() {
        return root;
    }

    /** Replaces the devices, e.g. with those read from the settings, and selects the named one or the first. */
    public void setDevices(List<SavedDevice> saved, Optional<String> selected) {
        closeSession();
        loadingDevices = true;
        try {
            devices.setAll(saved.stream().map(Device::loaded).toList());
            device.setValue(devices.stream().filter(d -> selected.map(d.name()::equals).orElse(false)).findFirst()
                    .orElse(devices.isEmpty() ? null : devices.getFirst()));
        } finally {
            loadingDevices = false;
        }
    }

    /** The devices to save, most recently used first; secrets are kept only for devices that remember them. */
    public List<SavedDevice> savedDevices() {
        return devices.stream().map(Device::saved).toList();
    }

    /** Name of the selected device. */
    public Optional<String> selectedDevice() {
        return Optional.ofNullable(device.getValue()).map(Device::name);
    }

    /** Widths of the results table columns, by column title. */
    public Map<String, Double> columnWidths() {
        return results.getColumns().stream()
                .collect(Collectors.toMap(TableColumn::getText, TableColumn::getWidth, (a, _) -> a));
    }

    /** Restores column widths saved with {@link #columnWidths()}; unknown titles are ignored. */
    public void setColumnWidths(Map<String, Double> widths) {
        for (TableColumn<ResultFormatter.Formatted, ?> column : results.getColumns()) {
            Double width = widths.get(column.getText());
            if (width != null) {
                column.setPrefWidth(width);
            }
        }
    }

    /** Uses a new MIB model for naming and formatting future results. */
    public void setModel(MibModel model) {
        this.model = model;
        this.formatter = new ResultFormatter(model);
    }

    /** Puts the selected tree node's OID in the OID field; scalars get their {@code .0} instance. */
    public void setSelectedNode(OidTree.Node node) {
        if (node == null) {
            return;
        }
        boolean scalar = node.definition().map(d -> d.kind() == NodeKind.SCALAR).orElse(false);
        oid.setText(scalar ? node.oid().child(0).toString() : node.oid().toString());
    }

    /** Closes the open session; call when the application exits. */
    public void close() {
        cancel.set(true);
        closeSession();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Operations

    private void runGet() {
        parseOid().ifPresent(o -> request("GET", s -> s.get(List.of(o)), r -> { }));
    }

    private void runGetNext() {
        parseOid().ifPresent(o -> request("GETNEXT", s -> s.getNext(List.of(o)), r -> {
            if (!r.isEmpty() && !r.getFirst().value().isException()) {
                oid.setText(r.getFirst().oid().toString());
            }
        }));
    }

    private void runWalk() {
        Optional<Oid> root = parseOid();
        if (root.isEmpty() || formatter == null) {
            return;
        }
        Optional<DeviceProfile> ready = readyProfile();
        if (ready.isEmpty()) {
            return;
        }
        DeviceProfile profile = ready.get();
        ResultFormatter f = formatter;
        results.getItems().clear();
        start("Walking " + f.name(root.get()) + "…");
        stop.setDisable(false);
        long started = System.nanoTime();
        int[] received = {0};
        Background.run("snmp-walk", () -> session(profile).walk(root.get(), batch -> {
            List<ResultFormatter.Formatted> rows = batch.stream().map(f::format).toList();
            Platform.runLater(() -> {
                results.getItems().addAll(rows);
                received[0] += rows.size();
                status.setText("Walking… " + received[0] + " values");
            });
        }, cancel::get), result -> {
            stop.setDisable(true);
            finish(walkSummary(result, started), false);
        }, e -> {
            stop.setDisable(true);
            failed("Walk", e);
        });
    }

    private void runSet(Oid target) {
        if (formatter == null) {
            return;
        }
        Optional<DeviceProfile> ready = readyProfile();
        if (ready.isEmpty()) {
            return;
        }
        DeviceProfile profile = ready.get();
        ResultFormatter f = formatter;
        Optional<MibNode> node = model.tree().longestPrefix(target).flatMap(OidTree.Node::definition);
        start("Reading current value…");
        // Read the current value first, to prefill the dialog and pick the type for objects without MIB info.
        Background.run("snmp-get", () -> session(profile).get(List.of(target)), current -> {
            finish("", false);
            Optional<SnmpValue> value = current.stream().findFirst().map(VarBindResult::value);
            SetDialog.show(window(), target, f, node, value, model).ifPresent(vb ->
                    request("SET", s -> s.set(List.of(vb)), r -> { }));
        }, e -> {
            finish("", false);
            // The object may not exist yet (row creation); let the user set it anyway.
            SetDialog.show(window(), target, f, node, Optional.empty(), model).ifPresent(vb ->
                    request("SET", s -> s.set(List.of(vb)), r -> { }));
        });
    }

    /** Functional request against a session. */
    private interface Request {
        List<VarBindResult> run(SnmpSession session) throws SnmpException;
    }

    /** Raw results and their formatted rows. */
    private record Done(List<VarBindResult> raw, List<ResultFormatter.Formatted> rows) {
    }

    private void request(String operation, Request request, Consumer<List<VarBindResult>> after) {
        if (formatter == null) {
            return;
        }
        Optional<DeviceProfile> ready = readyProfile();
        if (ready.isEmpty()) {
            return;
        }
        DeviceProfile profile = ready.get();
        ResultFormatter f = formatter;
        start(operation + "…");
        long started = System.nanoTime();
        Background.run("snmp-request", () -> {
            List<VarBindResult> raw = request.run(session(profile));
            return new Done(raw, raw.stream().map(f::format).toList());
        }, done -> {
            results.getItems().addAll(done.rows());
            if (!done.rows().isEmpty()) {
                results.scrollTo(results.getItems().size() - 1);
                results.getSelectionModel().clearAndSelect(results.getItems().size() - 1);
            }
            finish(operation + " · " + elapsed(started), false);
            after.accept(done.raw());
        }, e -> failed(operation, e));
    }

    private String walkSummary(WalkResult result, long started) {
        String end = switch (result.end()) {
            case END_OF_SUBTREE -> "end of subtree";
            case END_OF_MIB_VIEW -> "end of MIB view";
            case SINGLE_GET -> "single instance";
            case CANCELLED -> "stopped";
        };
        return "Walk · " + result.count() + (result.count() == 1 ? " value · " : " values · ") + end + " · "
                + elapsed(started);
    }

    private void start(String message) {
        cancel.set(false);
        busy.set(true);
        status.getStyleClass().remove("error-text");
        status.setText(message);
    }

    private void finish(String message, boolean error) {
        busy.set(false);
        status.getStyleClass().remove("error-text");
        if (error) {
            status.getStyleClass().add("error-text");
        }
        status.setText(message);
        status.setTooltip(message.isEmpty() ? null : new Tooltip(message));
    }

    private void failed(String operation, Throwable e) {
        String message = e instanceof SnmpException s ? s.getMessage() : String.valueOf(e);
        finish(operation + " failed: " + message, true);
    }

    private static String elapsed(long started) {
        long ms = (System.nanoTime() - started) / 1_000_000;
        return ms < 1000 ? ms + " ms" : String.format("%.1f s", ms / 1000.0);
    }

    private Optional<Oid> parseOid() {
        String text = oid.getText().strip();
        if (text.isEmpty() || model == null) {
            finish("Enter an OID", true);
            return Optional.empty();
        }
        try {
            SnmpValue value = ValueParser.parse(text, BaseType.OBJECT_IDENTIFIER, Optional.empty(), false, model);
            return Optional.of(((SnmpValue.ObjectId) value).oid());
        } catch (IllegalArgumentException e) {
            finish(e.getMessage(), true);
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Sessions and devices

    private synchronized SnmpSession session(DeviceProfile profile) throws SnmpException {
        if (session == null || !session.profile().equals(profile)) {
            if (session != null) {
                session.close();
            }
            session = SnmpSession.open(profile);
        }
        return session;
    }

    private synchronized void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    /**
     * The selected device's profile, ready for requests: if its secrets are not known yet, asks for them first.
     * The device moves to the top of the list, which is kept most recently used first. Empty if no device is
     * selected or the user cancelled.
     */
    private Optional<DeviceProfile> readyProfile() {
        Device current = device.getValue();
        if (current == null) {
            return Optional.empty();
        }
        Device ready = current;
        if (!current.secretsKnown()) {
            Optional<Device> entered = SecretsDialog.show(window(), current);
            if (entered.isEmpty()) {
                return Optional.empty();
            }
            replace(current, entered.get());
            ready = entered.get();
        }
        moveToTop(ready);
        resultsDevice = ready.profile().toString();
        return Optional.of(ready.profile());
    }

    private void moveToTop(Device used) {
        if (devices.indexOf(used) <= 0) {
            return;
        }
        loadingDevices = true;
        try {
            devices.remove(used);
            devices.add(0, used);
            device.setValue(used);
        } finally {
            loadingDevices = false;
        }
        devicesChanged.run();
    }

    private void editDevice(Optional<Device> existing) {
        Device initial = existing.orElseGet(() -> new Device(
                DeviceProfile.v2c(uniqueName("device"), "", "public", "private"), false, true));
        Set<String> otherNames = devices.stream().filter(d -> existing.map(e -> e != d).orElse(true))
                .map(Device::name).collect(Collectors.toSet());
        DeviceProfileDialog.show(window(), initial, otherNames, () -> formatter).ifPresent(d -> {
            if (existing.isPresent()) {
                replace(existing.get(), d);
            } else {
                devices.add(0, d);
                device.setValue(d);
                devicesChanged.run();
            }
        });
    }

    private void replace(Device old, Device updated) {
        loadingDevices = true;
        try {
            devices.set(devices.indexOf(old), updated);
            closeSession();
            device.setValue(updated);
        } finally {
            loadingDevices = false;
        }
        devicesChanged.run();
    }

    private void removeDevice() {
        Device current = device.getValue();
        if (current == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "The device and its settings are deleted.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(window());
        confirm.setTitle("Remove device");
        confirm.setHeaderText("Remove " + current.name() + "?");
        if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) {
            return;
        }
        closeSession();
        loadingDevices = true;
        try {
            devices.remove(current);
            device.getSelectionModel().selectFirst();
        } finally {
            loadingDevices = false;
        }
        devicesChanged.run();
    }

    private String uniqueName(String base) {
        Set<String> names = devices.stream().map(Device::name).collect(Collectors.toSet());
        String name = base;
        for (int i = 2; names.contains(name); i++) {
            name = base + "-" + i;
        }
        return name;
    }

    private Window window() {
        return root.getScene().getWindow();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Results table

    private void createResultsTable() {
        results.getColumns().setAll(List.of(
                column("Name", 260, ResultFormatter.Formatted::name),
                column("Value", 360, ResultFormatter.Formatted::value),
                column("Type", 120, ResultFormatter.Formatted::type),
                column("OID", 260, r -> r.oid().toString()),
                column("Raw", 220, ResultFormatter.Formatted::raw)));
        results.setPlaceholder(new Label("Select a node in the tree, then Get, Get Next or Walk."));
        results.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        results.setRowFactory(_ -> {
            TableRow<ResultFormatter.Formatted> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    showInTree.accept(row.getItem().oid());
                }
            });
            return row;
        });

        MenuItem showInTreeItem = new MenuItem("Show in tree");
        showInTreeItem.setOnAction(_ -> selected().ifPresent(r -> showInTree.accept(r.oid())));
        MenuItem setItem = new MenuItem("Set…");
        setItem.setOnAction(_ -> selected().ifPresent(r -> runSet(r.oid())));
        MenuItem useOid = new MenuItem("Use as OID");
        useOid.setOnAction(_ -> selected().ifPresent(r -> oid.setText(r.oid().toString())));
        MenuItem copyValue = new MenuItem("Copy value");
        copyValue.setOnAction(_ -> copy(ResultFormatter.Formatted::value));
        MenuItem copyOid = new MenuItem("Copy OID");
        copyOid.setOnAction(_ -> copy(r -> r.oid().toString()));
        MenuItem copyName = new MenuItem("Copy name");
        copyName.setOnAction(_ -> copy(ResultFormatter.Formatted::name));
        MenuItem copyRows = new MenuItem("Copy rows");
        copyRows.setOnAction(_ -> copy(r -> r.name() + "\t" + r.oid() + "\t" + r.type() + "\t" + r.value()));
        MenuItem exportSelected = new MenuItem("Export selected rows…");
        exportSelected.setOnAction(_ -> export(true));
        ContextMenu menu = new ContextMenu(showInTreeItem, useOid, setItem, new SeparatorMenuItem(), copyValue, copyOid,
                copyName, copyRows, new SeparatorMenuItem(), exportSelected);
        menu.setOnShowing(_ -> {
            setItem.setDisable(busy.get());
            exportSelected.setDisable(busy.get() || results.getSelectionModel().isEmpty());
        });
        results.setContextMenu(menu);
    }

    /** Saves all results, or the selected rows, to a CSV or text file chosen by the user. */
    private void export(boolean selectedOnly) {
        List<ResultFormatter.Formatted> rows = List.copyOf(selectedOnly
                ? results.getSelectionModel().getSelectedItems()
                : results.getItems());
        if (rows.isEmpty()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(selectedOnly ? "Export selected rows" : "Export results");
        for (ResultExport.Format f : ResultExport.Format.values()) {
            FileChooser.ExtensionFilter filter = new FileChooser.ExtensionFilter(
                    f + " (*." + f.extension() + ")", "*." + f.extension());
            chooser.getExtensionFilters().add(filter);
            if (f == exportFormat) {
                chooser.setSelectedExtensionFilter(filter);
            }
        }
        if (exportDirectory != null && Files.isDirectory(exportDirectory)) {
            chooser.setInitialDirectory(exportDirectory.toFile());
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        chooser.setInitialFileName("snmp-results-" + now.format(FILE_TIME) + "." + exportFormat.extension());
        File chosen = chooser.showSaveDialog(window());
        if (chosen == null) {
            return;
        }
        int filterIndex = chooser.getExtensionFilters().indexOf(chooser.getSelectedExtensionFilter());
        ResultExport.Format chosenFormat = filterIndex >= 0 ? ResultExport.Format.values()[filterIndex] : exportFormat;
        Path file = chosen.toPath();
        Optional<ResultExport.Format> byName = ResultExport.Format.forFile(file);
        if (byName.isEmpty()) {
            // Some platforms' dialogs do not add the extension of the chosen filter.
            file = file.resolveSibling(file.getFileName() + "." + chosenFormat.extension());
        }
        ResultExport.Format format = byName.orElse(chosenFormat);
        exportDirectory = file.toAbsolutePath().getParent();
        exportFormat = format;
        List<String> header = List.of(
                "SNMP Tool results" + (resultsDevice.isEmpty() ? "" : " from " + resultsDevice),
                "Exported " + now.toString().replace('T', ' '),
                rows.size() + (rows.size() == 1 ? " value" : " values"));
        Path target = file;
        start("Exporting…");
        Background.run("export", () -> {
            ResultExport.write(target, format, rows, header);
            return target;
        }, written -> finish("Exported " + rows.size() + (rows.size() == 1 ? " value" : " values") + " to "
                + written.getFileName(), false), e -> finish("Export failed: " + e.getMessage(), true));
    }

    private Optional<ResultFormatter.Formatted> selected() {
        return Optional.ofNullable(results.getSelectionModel().getSelectedItem());
    }

    private void copy(Function<ResultFormatter.Formatted, String> text) {
        String joined = results.getSelectionModel().getSelectedItems().stream().map(text).collect(Collectors.joining("\n"));
        if (!joined.isEmpty()) {
            Clipboards.copy(joined);
        }
    }

    private static TableColumn<ResultFormatter.Formatted, String> column(String title, double width,
            Function<ResultFormatter.Formatted, String> value) {
        TableColumn<ResultFormatter.Formatted, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(c -> new SimpleStringProperty(value.apply(c.getValue())));
        return column;
    }
}
