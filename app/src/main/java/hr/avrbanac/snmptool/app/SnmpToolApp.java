package hr.avrbanac.snmptool.app;

import javafx.application.Application;
import javafx.scene.Scene;
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
        scene.getStylesheets().add(SnmpToolApp.class.getResource("app.css").toExternalForm());

        stage.setTitle(TITLE);
        stage.setMinWidth(800);
        stage.setMinHeight(500);
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
