package vn.wisense.client;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import vn.wisense.client.alert.FallAlertView;
import vn.wisense.client.ui.LoginView;
import vn.wisense.client.ui.MainView;

/** Mở màn đăng nhập trước; đăng nhập xong thì sang màn hình chính, đăng xuất thì quay lại. */
public class WiSenseApp extends Application {
    private final String clientName = MainView.defaultName();
    private Stage stage;
    private Scene scene;
    private FallAlertView alerts;
    private MainView view;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        alerts = new FallAlertView(stage);                     // popup, âm thanh, danh sách té ngã (TV4)
        scene = new Scene(new StackPane(), 1000, 760);
        stage.setScene(scene);
        showLogin(null);
        stage.show();
    }

    private void showLogin(String notice) {
        view = null;
        stage.setTitle("WiSense: đăng nhập");
        scene.setRoot(new StackPane(new LoginView(clientName, notice, this::showMain).root()));
    }

    private void showMain(LoginView.Session session) {
        view = new MainView(session, this::showLogin);
        view.setFallAlertHandler(alerts);
        BorderPane app = new BorderPane(view.root());
        app.setBottom(alerts.node());
        String user = session.welcome().user();
        stage.setTitle(user == null ? "WiSense" : "WiSense: " + user);
        scene.setRoot(app);
    }

    @Override
    public void stop() {
        if (view != null) view.disconnect();
    }
}
