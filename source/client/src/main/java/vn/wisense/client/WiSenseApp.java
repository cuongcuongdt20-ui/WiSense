package vn.wisense.client;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import vn.wisense.client.ui.MainView;
import javafx.scene.layout.BorderPane;
import vn.wisense.client.alert.FallAlertView;

public class WiSenseApp extends Application {
    private MainView view;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        view = new MainView();
        FallAlertView alerts = new FallAlertView(stage);       // popup, âm thanh, danh sách té ngã (TV4)
        view.setFallAlertHandler(alerts);
        BorderPane app = new BorderPane(view.root());
        app.setBottom(alerts.node());
        stage.setTitle("WiSense");
        stage.setScene(new Scene(app, 1000, 760));
        stage.show();
    }

    @Override
    public void stop() {
        view.disconnect();
    }
}
