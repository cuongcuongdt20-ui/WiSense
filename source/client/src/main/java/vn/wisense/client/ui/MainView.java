package vn.wisense.client.ui;

import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import javafx.scene.control.TextInputDialog;
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
    private final Button rateButton = new Button("Tần số");
    private final Label sensorState = new Label();
    private final Label statsLabel = new Label("—");
    private final Label errorLabel = new Label();
    private final SignalChart chart = new SignalChart();
    private final StatusPanel status = new StatusPanel();
    private final String clientName = defaultName();
    private final Set<Integer> online = new HashSet<>();    // chỉ luồng JavaFX dùng
    private volatile boolean connected;
    private ServerConnection conn;
    private int shown = 1;                                  // sensor đang hiển thị
    private long nextReq = 1;                               // mã yêu cầu cho lệnh SET_SENSOR
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
        rateButton.setDisable(true);
        rateButton.setOnAction(e -> askSampleRate());
        HBox top = new HBox(8, new Label("Server"), hostField, new Label("Cổng"), portField, connect, connLabel,
                new Label("Cảm biến"), sensorBox, sensorState, historyButton, rateButton);
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
            rateButton.setDisable(false);
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
        showSensorState();
        if (conn != null) conn.setSensors(List.of(s));
    }

    /** Hỏi tần số lấy mẫu mới rồi gửi SET_SENSOR; server chuyển xuống cảm biến, OK hoặc ERROR trả về sau. */
    private void askSampleRate() {
        TextInputDialog dialog = new TextInputDialog("100");
        dialog.setTitle("Đổi tần số lấy mẫu");
        dialog.setHeaderText("Cảm biến " + shown + ": số lần ping router mỗi giây");
        dialog.setContentText("Từ 1 đến 200:");
        dialog.showAndWait().ifPresent(text -> {
            try {
                conn.request(Msg.setSensor(nextReq++, shown, Integer.parseInt(text.trim())));
            } catch (NumberFormatException e) {
                showError("Tần số phải là số nguyên");
            }
        });
    }

    private void showSensorState() {
        boolean on = online.contains(shown);
        sensorState.setText(on ? "● online" : "○ offline");
        sensorState.setTextFill(on ? Color.SEAGREEN : Color.GRAY);
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
                case Message.Welcome w -> {
                    online.clear();
                    online.addAll(w.online());
                    showSensors(w.sensors());
                    showSensorState();
                }
                case Message.SensorStatus st -> onSensorStatus(st);
                case Message.History h -> showHistory(h);
                case Message.Ok ok when Msg.SET_SENSOR.equals(ok.re()) -> showNotice("Cảm biến đã áp dụng tần số mới");
                case Message.ServerError err -> showError(err.code() + ": " + err.msg());
                default -> { }                              // PING, OK khác, tin của sensor khác
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

    /** Cảm biến online/offline: cập nhật danh sách, báo nếu là cảm biến đang xem. */
    private void onSensorStatus(Message.SensorStatus st) {
        if (st.online()) online.add(st.sensor()); else online.remove(st.sensor());
        if (!sensorBox.getItems().contains(st.sensor())) {
            sensorBox.getItems().add(st.sensor());           // cảm biến mới xuất hiện sau khi đã kết nối
            sensorBox.setDisable(false);
        }
        if (st.sensor() == shown) {
            showSensorState();
            if (!st.online()) showError("Cảm biến " + st.sensor() + " mất kết nối");
        }
    }

    private void showError(String text) {
        errorLabel.setTextFill(Color.CRIMSON);
        errorLabel.setText(text);
        errorUntilMs = System.currentTimeMillis() + 5000;
    }

    private void showNotice(String text) {
        errorLabel.setTextFill(Color.SEAGREEN);
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
