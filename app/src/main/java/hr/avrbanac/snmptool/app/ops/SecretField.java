package hr.avrbanac.snmptool.app.ops;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

/**
 * A password field that can show its text in clear, e.g. for communities and passphrases.
 */
final class SecretField extends StackPane {

    private final PasswordField hidden = new PasswordField();
    private final TextField shown = new TextField();

    SecretField(BooleanProperty show) {
        shown.textProperty().bindBidirectional(hidden.textProperty());
        shown.visibleProperty().bind(show);
        hidden.visibleProperty().bind(show.not());
        getChildren().addAll(hidden, shown);
    }

    StringProperty textProperty() {
        return hidden.textProperty();
    }

    String getText() {
        return hidden.getText();
    }

    void setText(String text) {
        hidden.setText(text);
    }
}
