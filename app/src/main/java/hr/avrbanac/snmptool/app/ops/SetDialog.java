package hr.avrbanac.snmptool.app.ops;

import hr.avrbanac.snmptool.core.mib.model.BaseType;
import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.model.MibNode;
import hr.avrbanac.snmptool.core.mib.model.MibType;
import hr.avrbanac.snmptool.core.mib.model.Oid;
import hr.avrbanac.snmptool.core.snmp.ResultFormatter;
import hr.avrbanac.snmptool.core.snmp.SnmpValue;
import hr.avrbanac.snmptool.core.snmp.ValueParser;
import hr.avrbanac.snmptool.core.snmp.VarBindResult;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

/**
 * Asks for the value of a SET, validated against the MIB type as the user types.
 */
final class SetDialog {

    private static final Set<String> WRITABLE_ACCESS = Set.of("read-write", "read-create", "write-only");

    private final Dialog<VarBindResult> dialog = new Dialog<>();
    private final Oid target;
    private final Optional<MibType> syntax;
    private final MibModel model;
    private final ChoiceBox<BaseType> type = new ChoiceBox<>();
    private final TextField text = new TextField();
    private final ComboBox<String> choice = new ComboBox<>();
    private final CheckBox hex = new CheckBox("Hex");
    private final Label hint = new Label();
    private final Label error = new Label();
    private final ButtonType setButton = new ButtonType("Set", ButtonBar.ButtonData.OK_DONE);

    private SetDialog(Window owner, Oid target, ResultFormatter formatter, Optional<MibNode> node,
            Optional<SnmpValue> current, MibModel model) {
        this.target = target;
        this.syntax = node.flatMap(MibNode::syntax);
        this.model = model;
        dialog.initOwner(owner);
        dialog.setTitle("Set value");
        dialog.setHeaderText("Set " + formatter.name(target));
        dialog.setResizable(true);

        type.getItems().setAll(ValueParser.WRITABLE_TYPES);
        type.setConverter(new StringConverter<>() {
            @Override
            public String toString(BaseType t) {
                return t == null ? "" : t.text();
            }

            @Override
            public BaseType fromString(String s) {
                return null;
            }
        });
        type.setValue(initialType(current));
        hex.setSelected(syntax.map(ValueParser::prefersHex).orElse(false)
                || current.filter(v -> v instanceof SnmpValue.OctetString s && !isText(s.bytes())).isPresent());

        GridPane info = new GridPane();
        info.setHgap(10);
        info.setVgap(4);
        int row = 0;
        info.addRow(row++, key("OID"), new Label(target.toString()));
        if (syntax.isPresent()) {
            info.addRow(row++, key("Syntax"), new Label(syntax.get().syntaxText()));
        }
        node.flatMap(MibNode::access).ifPresent(a -> info.addRow(info.getRowCount(), key("Access"), new Label(a)));
        current.ifPresent(v -> info.addRow(info.getRowCount(), key("Current"),
                new Label(formatter.format(new VarBindResult(target, v)).value())));

        StackPane input = new StackPane(text, choice);
        text.visibleProperty().bind(choice.visibleProperty().not());
        choice.setMaxWidth(Double.MAX_VALUE);
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(6);
        form.addRow(0, key("Type"), type);
        form.addRow(1, key("Value"), input, hex);
        GridPane.setHgrow(input, Priority.ALWAYS);
        hint.setWrapText(true);
        hint.getStyleClass().add("placeholder");
        error.setWrapText(true);
        error.getStyleClass().add("error-text");

        VBox content = new VBox(10, info, form, hint, error);
        node.flatMap(MibNode::access).filter(a -> !WRITABLE_ACCESS.contains(a)).ifPresent(a -> {
            Label warning = new Label("The MIB declares this object " + a + "; the agent will probably refuse the set.");
            warning.setWrapText(true);
            warning.getStyleClass().add("warning-text");
            content.getChildren().add(1, warning);
        });
        content.setPadding(new Insets(4));
        content.setPrefWidth(520);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(setButton, ButtonType.CANCEL);
        dialog.setResultConverter(b -> b == setButton ? parse().map(v -> new VarBindResult(target, v)).orElse(null) : null);

        type.valueProperty().addListener((_, _, _) -> updateInput(Optional.empty()));
        hex.selectedProperty().addListener((_, _, _) -> validate());
        text.textProperty().addListener((_, _, _) -> validate());
        choice.valueProperty().addListener((_, _, _) -> validate());
        updateInput(current);
        dialog.setOnShown(_ -> (choice.isVisible() ? choice : text).requestFocus());
    }

    /**
     * Shows the dialog.
     *
     * @param current the value read just before, used to prefill the input
     * @return the variable binding to set, or empty if cancelled
     */
    static Optional<VarBindResult> show(Window owner, Oid target, ResultFormatter formatter, Optional<MibNode> node,
            Optional<SnmpValue> current, MibModel model) {
        return new SetDialog(owner, target, formatter, node, current.filter(v -> !v.isException()), model)
                .dialog.showAndWait();
    }

    /** The MIB constraints apply only when the chosen type is the MIB's own. */
    private Optional<MibType> constraints() {
        return syntax.filter(t -> writeType(t.baseType()) == type.getValue());
    }

    private void updateInput(Optional<SnmpValue> current) {
        Optional<MibType> t = constraints();
        boolean enumerated = t.isPresent() && !t.get().namedValues().isEmpty() && type.getValue() == BaseType.INTEGER;
        choice.setVisible(enumerated);
        hex.setVisible(type.getValue() == BaseType.OCTET_STRING);
        if (enumerated) {
            choice.getItems().setAll(t.get().namedValues().stream().map(MibType.NamedValue::toString).toList());
            current.filter(SnmpValue.Integer32.class::isInstance)
                    .flatMap(v -> t.get().label(BigInteger.valueOf(((SnmpValue.Integer32) v).value()))
                            .map(l -> l + "(" + ((SnmpValue.Integer32) v).value() + ")"))
                    .ifPresent(choice::setValue);
        } else {
            current.map(this::inputText).ifPresent(text::setText);
        }
        validate();
    }

    private String inputText(SnmpValue v) {
        return switch (v) {
            case SnmpValue.Integer32 i -> Integer.toString(i.value());
            case SnmpValue.Gauge32 g -> Long.toString(g.value());
            case SnmpValue.Counter32 c -> Long.toString(c.value());
            case SnmpValue.Counter64 c -> c.value().toString();
            case SnmpValue.TimeTicks t -> Long.toString(t.value());
            case SnmpValue.OctetString s -> hex.isSelected() ? HexFormat.ofDelimiter(" ").formatHex(s.bytes())
                    : new String(s.bytes(), StandardCharsets.UTF_8);
            case SnmpValue.ObjectId o -> o.oid().toString();
            case SnmpValue.IpAddress ip -> ip.toString();
            case SnmpValue.Opaque o -> HexFormat.ofDelimiter(" ").formatHex(o.bytes());
            default -> "";
        };
    }

    private Optional<SnmpValue> parse() {
        String input = choice.isVisible() ? Optional.ofNullable(choice.getValue()).orElse("") : text.getText();
        try {
            return Optional.of(ValueParser.parse(input, type.getValue(), constraints(),
                    hex.isVisible() && hex.isSelected(), model));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private void validate() {
        hint.setText(ValueParser.hint(type.getValue(), constraints(), hex.isVisible() && hex.isSelected()));
        String input = choice.isVisible() ? Optional.ofNullable(choice.getValue()).orElse("") : text.getText();
        String message = "";
        try {
            ValueParser.parse(input, type.getValue(), constraints(), hex.isVisible() && hex.isSelected(), model);
        } catch (IllegalArgumentException e) {
            message = e.getMessage();
        }
        error.setText(message);
        dialog.getDialogPane().lookupButton(setButton).setDisable(!message.isEmpty());
    }

    private BaseType initialType(Optional<SnmpValue> current) {
        if (syntax.isPresent() && ValueParser.WRITABLE_TYPES.contains(writeType(syntax.get().baseType()))) {
            return writeType(syntax.get().baseType());
        }
        return current.map(v -> switch (v) {
            case SnmpValue.Integer32 _ -> BaseType.INTEGER;
            case SnmpValue.Gauge32 _ -> BaseType.GAUGE32;
            case SnmpValue.Counter32 _ -> BaseType.COUNTER32;
            case SnmpValue.Counter64 _ -> BaseType.COUNTER64;
            case SnmpValue.TimeTicks _ -> BaseType.TIMETICKS;
            case SnmpValue.ObjectId _ -> BaseType.OBJECT_IDENTIFIER;
            case SnmpValue.IpAddress _ -> BaseType.IP_ADDRESS;
            case SnmpValue.Opaque _ -> BaseType.OPAQUE;
            default -> BaseType.OCTET_STRING;
        }).orElse(BaseType.OCTET_STRING);
    }

    /** MIB base types that are written as another wire type. */
    private static BaseType writeType(BaseType base) {
        return switch (base) {
            case INTEGER32 -> BaseType.INTEGER;
            case NETWORK_ADDRESS -> BaseType.IP_ADDRESS;
            default -> base;
        };
    }

    private static boolean isText(byte[] bytes) {
        for (byte b : bytes) {
            if ((b & 0xFF) < 32 && b != '\n' && b != '\r' && b != '\t') {
                return false;
            }
        }
        return true;
    }

    private static Label key(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("details-key");
        return l;
    }
}
