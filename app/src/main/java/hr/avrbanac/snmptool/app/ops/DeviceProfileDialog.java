package hr.avrbanac.snmptool.app.ops;

import hr.avrbanac.snmptool.app.Background;
import hr.avrbanac.snmptool.core.mib.model.Oid;
import hr.avrbanac.snmptool.core.snmp.DeviceProfile;
import hr.avrbanac.snmptool.core.snmp.ResultFormatter;
import hr.avrbanac.snmptool.core.snmp.SnmpSession;
import hr.avrbanac.snmptool.core.snmp.SnmpVersion;
import hr.avrbanac.snmptool.core.snmp.V3Security;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Creates or edits a {@link DeviceProfile}, with live validation and a connection test.
 */
final class DeviceProfileDialog {

    private static final Oid SYS_DESCR = Oid.parse("1.3.6.1.2.1.1.1.0");
    private static final Oid SYS_UP_TIME = Oid.parse("1.3.6.1.2.1.1.3.0");

    private final Dialog<DeviceProfile> dialog = new Dialog<>();
    private final Set<String> otherNames;
    private final Supplier<ResultFormatter> formatter;
    private final SimpleBooleanProperty showSecrets = new SimpleBooleanProperty();

    private final TextField name = new TextField();
    private final TextField host = new TextField();
    private final Spinner<Integer> port = new Spinner<>(1, 65535, DeviceProfile.DEFAULT_PORT);
    private final ChoiceBox<SnmpVersion> version = new ChoiceBox<>();
    private final Spinner<Integer> timeout = new Spinner<>(100, 60000, 2000, 500);
    private final Spinner<Integer> retries = new Spinner<>(0, 10, 1);
    private final Spinner<Integer> maxRepetitions = new Spinner<>(1, 1000, 25, 5);
    private final SecretField readCommunity = new SecretField(showSecrets);
    private final SecretField writeCommunity = new SecretField(showSecrets);
    private final TextField user = new TextField();
    private final ChoiceBox<V3Security.Level> level = new ChoiceBox<>();
    private final ChoiceBox<V3Security.AuthProtocol> authProtocol = new ChoiceBox<>();
    private final SecretField authPassword = new SecretField(showSecrets);
    private final ChoiceBox<V3Security.PrivProtocol> privProtocol = new ChoiceBox<>();
    private final SecretField privPassword = new SecretField(showSecrets);
    private final TextField contextName = new TextField();
    private final TextField contextEngineId = new TextField();
    private final Label problems = new Label();
    private final Label testResult = new Label();
    private final ButtonType okButton = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);

    private DeviceProfileDialog(Window owner, DeviceProfile initial, Set<String> otherNames,
            Supplier<ResultFormatter> formatter) {
        this.otherNames = otherNames;
        this.formatter = formatter;
        dialog.initOwner(owner);
        dialog.setTitle("Device");
        dialog.setHeaderText("SNMP agent connection settings");
        dialog.setResizable(true);

        version.getItems().setAll(SnmpVersion.values());
        level.getItems().setAll(V3Security.Level.values());
        authProtocol.getItems().setAll(V3Security.AuthProtocol.values());
        privProtocol.getItems().setAll(V3Security.PrivProtocol.values());
        port.setEditable(true);
        timeout.setEditable(true);
        retries.setEditable(true);
        maxRepetitions.setEditable(true);
        contextEngineId.setPromptText("hex, empty = discovered");
        contextName.setPromptText("empty = default context");
        load(initial);

        GridPane common = grid();
        row(common, 0, "Name", name);
        row(common, 1, "Host", host);
        row(common, 2, "Port", port);
        row(common, 3, "Version", version);
        row(common, 4, "Timeout (ms)", timeout);
        row(common, 5, "Retries", retries);
        row(common, 6, "Max repetitions", maxRepetitions);

        GridPane v2c = grid();
        row(v2c, 0, "Read community", readCommunity);
        row(v2c, 1, "Write community", writeCommunity);
        TitledPane v2cPane = new TitledPane("SNMPv2c", v2c);
        v2cPane.setCollapsible(false);

        GridPane v3 = grid();
        row(v3, 0, "User", user);
        row(v3, 1, "Security level", level);
        row(v3, 2, "Auth protocol", authProtocol);
        row(v3, 3, "Auth passphrase", authPassword);
        row(v3, 4, "Privacy protocol", privProtocol);
        row(v3, 5, "Privacy passphrase", privPassword);
        row(v3, 6, "Context name", contextName);
        row(v3, 7, "Context engine ID", contextEngineId);
        TitledPane v3Pane = new TitledPane("SNMPv3 (USM)", v3);
        v3Pane.setCollapsible(false);

        v2cPane.visibleProperty().bind(version.valueProperty().isEqualTo(SnmpVersion.V2C));
        v2cPane.managedProperty().bind(v2cPane.visibleProperty());
        v3Pane.visibleProperty().bind(version.valueProperty().isEqualTo(SnmpVersion.V3));
        v3Pane.managedProperty().bind(v3Pane.visibleProperty());

        CheckBox show = new CheckBox("Show communities and passphrases");
        show.selectedProperty().bindBidirectional(showSecrets);
        Button test = new Button("Test");
        test.setOnAction(_ -> test());
        problems.getStyleClass().add("error-text");
        problems.setWrapText(true);
        testResult.setWrapText(true);
        HBox testRow = new HBox(8, test, testResult);

        VBox content = new VBox(8, common, v2cPane, v3Pane, show, testRow, problems);
        content.setPadding(new Insets(4));
        content.setPrefWidth(480);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(okButton, ButtonType.CANCEL);
        dialog.setResultConverter(b -> b == okButton ? profile() : null);

        for (var p : List.of(name.textProperty(), host.textProperty(), readCommunity.textProperty(),
                writeCommunity.textProperty(), user.textProperty(), authPassword.textProperty(),
                privPassword.textProperty(), contextName.textProperty(), contextEngineId.textProperty())) {
            p.addListener((_, _, _) -> validate());
        }
        for (var p : List.of(version.valueProperty(), level.valueProperty(), authProtocol.valueProperty(),
                privProtocol.valueProperty(), port.valueProperty(), timeout.valueProperty(), retries.valueProperty(),
                maxRepetitions.valueProperty())) {
            p.addListener((_, _, _) -> validate());
        }
        level.valueProperty().addListener((_, _, _) -> updateLevel());
        updateLevel();
        validate();
        dialog.setOnShown(_ -> name.requestFocus());
    }

    /** Shows the dialog; {@code otherNames} are the names of the other profiles, which must not be reused. */
    static Optional<DeviceProfile> show(Window owner, DeviceProfile initial, Set<String> otherNames,
            Supplier<ResultFormatter> formatter) {
        return new DeviceProfileDialog(owner, initial, otherNames, formatter).dialog.showAndWait();
    }

    private void load(DeviceProfile p) {
        name.setText(p.name());
        host.setText(p.host());
        port.getValueFactory().setValue(p.port());
        version.setValue(p.version());
        timeout.getValueFactory().setValue(p.timeoutMillis());
        retries.getValueFactory().setValue(p.retries());
        maxRepetitions.getValueFactory().setValue(p.maxRepetitions());
        readCommunity.setText(p.readCommunity());
        writeCommunity.setText(p.writeCommunity());
        V3Security v3 = p.v3();
        user.setText(v3.user());
        level.setValue(v3.level());
        authProtocol.setValue(v3.authProtocol());
        authPassword.setText(v3.authPassword());
        privProtocol.setValue(v3.privProtocol());
        privPassword.setText(v3.privPassword());
        contextName.setText(v3.contextName());
        contextEngineId.setText(v3.contextEngineId());
    }

    private DeviceProfile profile() {
        return new DeviceProfile(name.getText().strip(), host.getText().strip(), port.getValue(), version.getValue(),
                timeout.getValue(), retries.getValue(), maxRepetitions.getValue(), readCommunity.getText(),
                writeCommunity.getText(), new V3Security(user.getText().strip(), level.getValue(),
                        authProtocol.getValue(), authPassword.getText(), privProtocol.getValue(),
                        privPassword.getText(), contextName.getText(), contextEngineId.getText().strip()));
    }

    private void updateLevel() {
        V3Security.Level l = level.getValue();
        authProtocol.setDisable(!l.authenticated());
        authPassword.setDisable(!l.authenticated());
        privProtocol.setDisable(!l.encrypted());
        privPassword.setDisable(!l.encrypted());
    }

    private void validate() {
        DeviceProfile p = profile();
        List<String> list = new ArrayList<>(p.problems());
        if (p.name().isEmpty()) {
            list.addFirst("Name is required");
        } else if (otherNames.contains(p.name())) {
            list.addFirst("Another device is already called " + p.name());
        }
        problems.setText(String.join("\n", list));
        dialog.getDialogPane().lookupButton(okButton).setDisable(!list.isEmpty());
    }

    private void test() {
        DeviceProfile p = profile();
        testResult.getStyleClass().remove("error-text");
        testResult.setText("Testing…");
        Background.run("snmp-test", () -> {
            try (SnmpSession session = SnmpSession.open(p)) {
                return session.get(List.of(SYS_DESCR, SYS_UP_TIME));
            }
        }, results -> testResult.setText("OK: " + results.stream()
                .map(r -> formatter.get().format(r).value())
                .map(v -> v.length() > 60 ? v.substring(0, 60) + "…" : v)
                .collect(Collectors.joining(" · "))), e -> {
                    testResult.getStyleClass().add("error-text");
                    testResult.setText("Failed: " + e.getMessage());
                });
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        return grid;
    }

    private static void row(GridPane grid, int row, String label, Node field) {
        grid.addRow(row, new Label(label), field);
        if (field instanceof Region r) {
            r.setMaxWidth(Double.MAX_VALUE);
        }
        GridPane.setHgrow(field, Priority.ALWAYS);
    }
}
