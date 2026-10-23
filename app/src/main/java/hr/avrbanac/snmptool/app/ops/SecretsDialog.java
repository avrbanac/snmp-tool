package hr.avrbanac.snmptool.app.ops;

import hr.avrbanac.snmptool.core.snmp.DeviceProfile;
import hr.avrbanac.snmptool.core.snmp.SnmpVersion;
import hr.avrbanac.snmptool.core.snmp.V3Security;
import java.util.Optional;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Asks for the communities or passphrases of a device whose secrets are not saved, once per run.
 */
final class SecretsDialog {

    private final Dialog<Device> dialog = new Dialog<>();
    private final Device device;
    private final SimpleBooleanProperty showSecrets = new SimpleBooleanProperty();
    private final SecretField readCommunity = new SecretField(showSecrets);
    private final SecretField writeCommunity = new SecretField(showSecrets);
    private final SecretField authPassword = new SecretField(showSecrets);
    private final SecretField privPassword = new SecretField(showSecrets);
    private final CheckBox remember = DeviceProfileDialog.rememberCheckBox();
    private final Label problems = new Label();
    private final ButtonType okButton = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);

    private SecretsDialog(Window owner, Device device) {
        this.device = device;
        DeviceProfile p = device.profile();
        dialog.initOwner(owner);
        dialog.setTitle("Device secrets");
        dialog.setHeaderText(p.version() == SnmpVersion.V2C
                ? "Communities for " + p.name() + " are not saved. Enter them for this session."
                : "Passphrases for " + p.name() + " (user " + p.v3().user() + ") are not saved. Enter them for this"
                        + " session.");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        if (p.version() == SnmpVersion.V2C) {
            grid.addRow(0, new Label("Read community"), readCommunity);
            grid.addRow(1, new Label("Write community"), writeCommunity);
        } else {
            grid.addRow(0, new Label("Auth passphrase (" + p.v3().authProtocol() + ")"), authPassword);
            if (p.v3().level().encrypted()) {
                grid.addRow(1, new Label("Privacy passphrase (" + p.v3().privProtocol() + ")"), privPassword);
            }
        }
        for (var field : grid.getChildren()) {
            if (field instanceof SecretField) {
                GridPane.setHgrow(field, Priority.ALWAYS);
            }
        }
        CheckBox show = new CheckBox("Show");
        show.selectedProperty().bindBidirectional(showSecrets);
        remember.setSelected(device.rememberSecrets());
        problems.getStyleClass().add("error-text");
        problems.setWrapText(true);

        VBox content = new VBox(8, grid, show, remember, problems);
        content.setPadding(new Insets(4));
        content.setPrefWidth(420);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(okButton, ButtonType.CANCEL);
        dialog.setResultConverter(b -> b == okButton ? new Device(profile(), remember.isSelected(), true) : null);
        for (SecretField f : new SecretField[] {readCommunity, writeCommunity, authPassword, privPassword}) {
            f.textProperty().addListener((_, _, _) -> validate());
        }
        validate();
        dialog.setOnShown(_ -> (p.version() == SnmpVersion.V2C ? readCommunity : authPassword).requestFocus());
    }

    /** Shows the dialog; returns the device with its secrets, or empty if cancelled. */
    static Optional<Device> show(Window owner, Device device) {
        return new SecretsDialog(owner, device).dialog.showAndWait();
    }

    private DeviceProfile profile() {
        DeviceProfile p = device.profile();
        V3Security v3 = p.v3();
        return new DeviceProfile(p.name(), p.host(), p.port(), p.version(), p.timeoutMillis(), p.retries(),
                p.maxRepetitions(), readCommunity.getText(), writeCommunity.getText(), new V3Security(v3.user(),
                        v3.level(), v3.authProtocol(), authPassword.getText(), v3.privProtocol(), privPassword.getText(),
                        v3.contextName(), v3.contextEngineId()));
    }

    private void validate() {
        String text = String.join("\n", profile().problems());
        problems.setText(text);
        dialog.getDialogPane().lookupButton(okButton).setDisable(!text.isEmpty());
    }
}
