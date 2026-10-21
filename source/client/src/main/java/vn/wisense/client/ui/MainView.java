package vn.wisense.client.ui;

import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import vn.wisense.client.alert.FallAlertHandler;
import vn.wisense.client.net.Message;
import vn.wisense.client.net.ServerConnection;
import vn.wisense.common.protocol.Msg;

/**
 * Màn hình chính, mở sau khi đăng nhập; AnimationTimer lấy tin từ hàng đợi mỗi khung hình rồi cập nhật giao diện.
 * Tài khoản viewer không thấy nút đổi tần số; bị server từ chối đăng nhập khi nối lại thì quay về màn đăng nhập.
 */
public class MainView {
    private static final int HISTORY_LIMIT = 50;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final ConcurrentLinkedQueue<Message> inbox;
    private final BorderPane root = new BorderPane();
    private final Label connLabel = new Label();
    private final Label userLabel = new Label();
    private final ComboBox<Integer> sensorBox = new ComboBox<>();
    private final Button historyButton = new Button("Lịch sử");
    private final Button adminButton = new Button("⚙ Quản trị");
    private final Label sensorState = new Label();
    private final Label statsLabel = new Label("—");
    private final Label errorLabel = new Label();
    private final SignalChart chart = new SignalChart();
    private final StatusPanel status = new StatusPanel();
    private final Set<Integer> online = new HashSet<>();    // chỉ luồng JavaFX dùng
    private final Consumer<String> onLogout;
    private final ServerConnection conn;
    private final AnimationTimer timer;
    private volatile boolean connected = true;
    private boolean closed;
    private int shown = 1;                                  // sensor đang hiển thị
    private AdminView admin;                                // chỉ tạo khi tài khoản admin bấm Quản trị
    private long errorUntilMs;
    private FallAlertHandler fallHandler = f -> System.out.println("FALL " + f);  // TV4 thay bằng bản thật

    /** onLogout nhận lời nhắn cho màn đăng nhập (null khi người dùng tự đăng xuất). */
    public MainView(LoginView.Session session, Consumer<String> onLogout) {
        this.conn = session.connection();
        this.inbox = session.inbox();
        this.onLogout = onLogout;
        conn.setOnStatus(ok -> connected = ok);
        Message.Welcome w = session.welcome();
        userLabel.setText(w.user() == null ? "" : "👤 " + w.user() + " (" + w.access() + ")");
        Button logout = new Button("Đăng xuất");
        logout.setOnAction(e -> close(null));
        sensorBox.setPromptText("Cảm biến");
        sensorBox.setDisable(true);
        sensorBox.setOnAction(e -> selectSensor(sensorBox.getValue()));
        historyButton.setOnAction(e -> conn.request(Msg.getHistory(shown, HISTORY_LIMIT)));
        // Đổi tần số, ngưỡng, tài khoản đều nằm trong cửa sổ Quản trị; viewer không thấy nút này,
        // và server vẫn chặn bằng FORBIDDEN nếu viewer cố gửi lệnh.
        String user = w.user() == null ? "admin" : w.user();
        adminButton.setOnAction(e -> {
            if (admin == null) admin = new AdminView(conn, user);
            admin.show();
        });
        adminButton.setVisible(w.isAdmin());
        adminButton.setManaged(w.isAdmin());
        HBox top = new HBox(8, connLabel, userLabel, new Label("Cảm biến"), sensorBox, sensorState,
                historyButton, adminButton, logout);
        top.setPadding(new Insets(8));
        errorLabel.setTextFill(Color.CRIMSON);
        HBox bottom = new HBox(16, statsLabel, errorLabel);
        bottom.setPadding(new Insets(6, 8, 6, 8));
        root.setTop(top);
        root.setCenter(chart.node());
        root.setRight(status.node());
        root.setBottom(bottom);                             // TV4 đặt lịch sử té ngã cạnh đây
        onWelcome(w);
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                drain();
            }
        };
        timer.start();
    }

    public Parent root() {
        return root;
    }

    public void setFallAlertHandler(FallAlertHandler h) {
        fallHandler = h;
    }

    /** Để phần cảnh báo (TV4) gửi lệnh nếu cần. */
    public ServerConnection connection() {
        return conn;
    }

    /** Đóng app: chỉ ngắt kết nối. */
    public void disconnect() {
        timer.stop();
        if (admin != null) admin.close();
        conn.stop();
    }

    /** Đăng xuất (notice = null) hoặc bị server từ chối khi nối lại: ngắt rồi quay về màn đăng nhập. */
    private void close(String notice) {
        if (closed) return;
        closed = true;
        disconnect();
        onLogout.accept(notice);
    }

    private void selectSensor(Integer s) {
        if (s == null) return;
        if (s != shown) {
            shown = s;
            chart.clear();
            status.setSensor(s);
            statsLabel.setText("—");
            showSensorState();
        }
        conn.setSensors(List.of(s));                        // chỉ nhận dữ liệu cảm biến đang xem
    }

    private void onWelcome(Message.Welcome w) {
        online.clear();
        online.addAll(w.online());
        showSensors(w.sensors());
        showSensorState();
    }

    /** Câu trả lời lệnh quản trị: chuyển sang cửa sổ Quản trị (đã đóng thì bỏ). */
    private void toAdmin(Message m) {
        if (admin != null) admin.handle(m);
    }

    private void showSensorState() {
        boolean on = online.contains(shown);
        sensorState.setText(on ? "● online" : "○ offline");
        sensorState.setTextFill(on ? Color.SEAGREEN : Color.GRAY);
    }

    private void drain() {
        connLabel.setText(connected ? "● Đã kết nối" : "● Đang nối lại...");
        connLabel.setTextFill(connected ? Color.SEAGREEN : Color.DARKORANGE);
        Message m;
        while (!closed && (m = inbox.poll()) != null) {
            switch (m) {
                case Message.Signal s when s.sensor() == shown -> chart.add(s.amp());
                case Message.Motion mo when mo.sensor() == shown -> status.showMotion(mo);
                case Message.Breath b when b.sensor() == shown -> status.showBreath(b);
                case Message.Stats st when st.sensor() == shown -> statsLabel.setText(
                        String.format("Cảm biến %d · %d gói/giây · mất %.1f%%", st.sensor(), st.pps(), st.loss() * 100));
                case Message.Fall f -> fallHandler.onFall(f);           // té ngã ở sensor nào cũng báo
                case Message.Welcome w -> onWelcome(w);            // nối lại sau khi rớt mạng
                case Message.SensorStatus st -> {
                    onSensorStatus(st);
                    if (admin != null && admin.isShowing()) admin.handle(st);
                }
                case Message.History h -> showHistory(h);
                case Message.Sensors x -> toAdmin(x);
                case Message.Users x -> toAdmin(x);
                case Message.Sessions x -> toAdmin(x);
                case Message.SensorKey x -> toAdmin(x);
                case Message.Ok ok when ok.req() >= AdminView.REQ_BASE -> toAdmin(ok);
                case Message.ServerError err when Msg.KICKED.equals(err.code()) ->
                        close("Quản trị viên đã ngắt kết nối của bạn: " + err.msg());
                case Message.ServerError err when Msg.AUTH_FAILED.equals(err.code())
                        || Msg.TOO_MANY_ATTEMPTS.equals(err.code()) -> close("Phiên đăng nhập không còn hợp lệ, đăng nhập lại");
                case Message.ServerError err when err.req() >= AdminView.REQ_BASE -> toAdmin(err);
                case Message.ServerError err when Msg.FORBIDDEN.equals(err.code()) ->
                        showError("Tài khoản không có quyền: " + err.msg());
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
            if (sensorBox.getValue() == null) sensorBox.setValue(st.sensor());   // chưa xem gì: xem luôn cảm biến này
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
    public static String defaultName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "pc";
        }
        return "ui-" + host + "-" + ProcessHandle.current().pid();
    }
}
