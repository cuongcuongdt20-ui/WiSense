package vn.wisense.client;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import vn.wisense.client.ui.MainView;

public class WiSenseApp extends Application {
    private MainView view;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        view = new MainView();
        stage.setTitle("WiSense");
        stage.setScene(new Scene(view.root(), 1000, 620));
        stage.show();
    }

    @Override
    public void stop() {
        view.disconnect();
    }
}
