package vn.wisense.client.ui;

import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import vn.wisense.client.alert.FallAlertHandler;
import vn.wisense.client.net.Message;
import vn.wisense.client.net.ServerConnection;
import vn.wisense.common.protocol.Msg;

/** Màn hình chính; AnimationTimer lấy tin từ hàng đợi mỗi khung hình rồi cập nhật giao diện. */
public class MainView {
    private static final int HISTORY_LIMIT = 50;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final ConcurrentLinkedQueue<Message> inbox = new ConcurrentLinkedQueue<>();
    private final BorderPane root = new BorderPane();
    private final TextField hostField = new TextField("127.0.0.1");
    private final TextField portField = new TextField("7000");
    private final Label connLabel = new Label("Chưa kết nối");
    private final ComboBox<Integer> sensorBox = new ComboBox<>();
    private final Button historyButton = new Button("Lịch sử");
    private final Label statsLabel = new Label("—");
    private final Label errorLabel = new Label();
    private final SignalChart chart = new SignalChart();
    private final StatusPanel status = new StatusPanel();
    private final String clientName = defaultName();
    private volatile boolean connected;
    private ServerConnection conn;
    private int shown = 1;                                  // sensor đang hiển thị
    private long errorUntilMs;
    private FallAlertHandler fallHandler = f -> System.out.println("FALL " + f);  // TV4 thay bằng bản thật

    public MainView() {
        Button connect = new Button("Kết nối");
        connect.setOnAction(e -> connect());
        portField.setPrefColumnCount(5);
        sensorBox.setPromptText("Cảm biến");
        sensorBox.setDisable(true);
        sensorBox.setOnAction(e -> selectSensor(sensorBox.getValue()));
        historyButton.setDisable(true);
        historyButton.setOnAction(e -> conn.request(Msg.getHistory(shown, HISTORY_LIMIT)));
        HBox top = new HBox(8, new Label("Server"), hostField, new Label("Cổng"), portField, connect, connLabel,
                new Label("Cảm biến"), sensorBox, historyButton);
        top.setPadding(new Insets(8));
        errorLabel.setTextFill(Color.CRIMSON);
        HBox bottom = new HBox(16, statsLabel, errorLabel);
        bottom.setPadding(new Insets(6, 8, 6, 8));
        root.setTop(top);
        root.setCenter(chart.node());
        root.setRight(status.node());
        root.setBottom(bottom);                             // TV4 đặt lịch sử té ngã cạnh đây
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

    /** Để phần cảnh báo (TV4) gửi lệnh nếu cần; null khi chưa bấm Kết nối. */
    public ServerConnection connection() {
        return conn;
    }

    public void disconnect() {
        if (conn != null) conn.stop();
    }

    private void connect() {
        disconnect();
        try {
            int port = Integer.parseInt(portField.getText().trim());
            conn = new ServerConnection(hostField.getText().trim(), port, clientName, inbox, ok -> connected = ok);
            conn.setSensors(List.of(shown));                // chưa nối nên chỉ ghi nhớ, gửi khi có WELCOME
            conn.start();
            historyButton.setDisable(false);
        } catch (NumberFormatException e) {
            connLabel.setText("Cổng không hợp lệ");
        }
    }

    private void selectSensor(Integer s) {
        if (s == null || s == shown) return;
        shown = s;
        chart.clear();
        status.setSensor(s);
        statsLabel.setText("—");
        if (conn != null) conn.setSensors(List.of(s));
    }

    private void drain() {
        if (conn != null) connLabel.setText(connected ? "● Đã kết nối" : "● Đang nối lại...");
        Message m;
        while ((m = inbox.poll()) != null) {
            switch (m) {
                case Message.Signal s when s.sensor() == shown -> chart.add(s.amp());
                case Message.Motion mo when mo.sensor() == shown -> status.showMotion(mo);
                case Message.Breath b when b.sensor() == shown -> status.showBreath(b);
                case Message.Stats st when st.sensor() == shown -> statsLabel.setText(
                        String.format("Cảm biến %d · %d gói/giây · mất %.1f%%", st.sensor(), st.pps(), st.loss() * 100));
                case Message.Fall f -> fallHandler.onFall(f);           // té ngã ở sensor nào cũng báo
                case Message.Welcome w -> showSensors(w.sensors());
                case Message.History h -> showHistory(h);
                case Message.ServerError err -> showError(err.code() + ": " + err.msg());
                default -> { }                              // PING, OK, tin của sensor khác
            }
        }
        if (errorUntilMs > 0 && System.currentTimeMillis() > errorUntilMs) {
            errorLabel.setText("");
            errorUntilMs = 0;
        }
        status.tick();
    }

    private void showSensors(List<Integer> sensors) {
        sensorBox.getItems().setAll(sensors);
        sensorBox.setDisable(sensors.isEmpty());
        if (sensors.contains(shown)) {
            sensorBox.setValue(shown);                      // đã SUBSCRIBE sensor này khi nhận WELCOME
        } else if (!sensors.isEmpty()) {
            sensorBox.setValue(sensors.get(0));             // gọi selectSensor qua setOnAction, gửi SUBSCRIBE mới
        }
    }

    private void showError(String text) {
        errorLabel.setText(text);
        errorUntilMs = System.currentTimeMillis() + 5000;
    }

    private void showHistory(Message.History h) {
        ListView<String> list = new ListView<>();
        for (Message e : h.events()) list.getItems().add(describe(e));
        if (list.getItems().isEmpty()) list.getItems().add("Chưa có sự kiện nào");
        Stage stage = new Stage();
        stage.setTitle("Lịch sử cảm biến " + h.sensor() + " (" + h.events().size() + " sự kiện, mới nhất trước)");
        stage.setScene(new Scene(list, 460, 400));
        stage.show();
    }

    private static String describe(Message e) {
        return switch (e) {
            case Message.Fall f -> String.format("%s  TÉ NGÃ  độ tin cậy %.2f",
                    CLOCK.format(Instant.ofEpochSecond(f.time())), f.confidence());
            case Message.Motion m -> String.format("CHUYỂN ĐỘNG  %s  mức %.2f", m.moving() ? "có" : "không", m.level());
            case Message.Breath b -> String.format("NHỊP THỞ  %.1f /phút  SNR %.1f", b.bpm(), b.snr());
            default -> e.toString();
        };
    }

    /** Tên cố định trong một lần chạy: server dùng tên này để gửi lại FALL khi client nối lại. */
    private static String defaultName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "pc";
        }
        return "ui-" + host + "-" + ProcessHandle.current().pid();
    }
}
