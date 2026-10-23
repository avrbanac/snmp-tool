package hr.avrbanac.snmptool.app;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/**
 * Application entry point.
 */
public final class SnmpToolApp extends Application {

    static final String TITLE = "SNMP Tool";

    @Override
    public void start(Stage stage) {
        MainWindow mainWindow = new MainWindow(stage);
        Scene scene = new Scene(mainWindow.root(), 1200, 800);
        scene.getStylesheets().add(stylesheet());

        stage.setTitle(TITLE);
        for (int size : new int[] {16, 32, 48, 64, 128, 256}) {
            stage.getIcons().add(new Image(SnmpToolApp.class.getResource("icons/snmp-tool-" + size + ".png")
                    .toExternalForm()));
        }
        stage.setMinWidth(800);
        stage.setMinHeight(500);
        stage.setScene(scene);
        mainWindow.applyLayout();
        stage.show();
    }

    /** URL of the application stylesheet. */
    static String stylesheet() {
        return SnmpToolApp.class.getResource("app.css").toExternalForm();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
