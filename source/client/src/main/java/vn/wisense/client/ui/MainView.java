package vn.wisense.client.ui;

import java.util.concurrent.ConcurrentLinkedQueue;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import vn.wisense.client.alert.FallAlertHandler;
import vn.wisense.client.net.Message;
import vn.wisense.client.net.ServerConnection;

/** Màn hình chính; AnimationTimer lấy tin từ hàng đợi mỗi khung hình rồi cập nhật giao diện. */
public class MainView {
    private final ConcurrentLinkedQueue<Message> inbox = new ConcurrentLinkedQueue<>();
    private final BorderPane root = new BorderPane();
    private final TextField hostField = new TextField("127.0.0.1");
    private final TextField portField = new TextField("7000");
    private final Label connLabel = new Label("Chưa kết nối");
    private final Label statsLabel = new Label("—");
    private final SignalChart chart = new SignalChart();
    private final StatusPanel status = new StatusPanel();
    private volatile boolean connected;
    private ServerConnection conn;
    private FallAlertHandler fallHandler = f -> System.out.println("FALL " + f);  // TV4 thay bằng bản thật

    public MainView() {
        Button connect = new Button("Kết nối");
        connect.setOnAction(e -> connect());
        portField.setPrefColumnCount(5);
        HBox top = new HBox(8, new Label("Server"), hostField, new Label("Cổng"), portField, connect, connLabel);
        top.setPadding(new Insets(8));
        statsLabel.setPadding(new Insets(6, 8, 6, 8));
        root.setTop(top);
        root.setCenter(chart.node());
        root.setRight(status.node());
        root.setBottom(statsLabel);                         // TV4 đặt lịch sử té ngã cạnh đây
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                drain();
            }
        }.start();
    }

    public Parent root() {
        return root;
    }

    public void setFallAlertHandler(FallAlertHandler h) {
        fallHandler = h;
    }

    public void disconnect() {
        if (conn != null) conn.stop();
    }

    private void connect() {
        disconnect();
        try {
            int port = Integer.parseInt(portField.getText().trim());
            conn = new ServerConnection(hostField.getText().trim(), port, inbox, ok -> connected = ok);
            conn.start();
        } catch (NumberFormatException e) {
            connLabel.setText("Cổng không hợp lệ");
        }
    }

    private void drain() {
        if (conn != null) connLabel.setText(connected ? "● Đã kết nối" : "● Đang nối lại...");
        Message m;
        while ((m = inbox.poll()) != null) {
            if (m.sensor() != 1) continue;                 // tạm thời chỉ hiện cảm biến 1
            switch (m) {
                case Message.Signal s -> chart.add(s.amp());
                case Message.Motion mo -> status.showMotion(mo);
                case Message.Breath b -> status.showBreath(b);
                case Message.Stats st -> statsLabel.setText(
                        String.format("%d gói/giây · mất %.1f%%", st.pps(), st.loss() * 100));
                case Message.Fall f -> fallHandler.onFall(f);
                case Message.Ping p -> { }
            }
        }
        status.tick();
    }
}
