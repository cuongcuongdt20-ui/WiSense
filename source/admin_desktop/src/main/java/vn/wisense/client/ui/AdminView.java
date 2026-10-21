package vn.wisense.client.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import vn.wisense.client.net.Message;
import vn.wisense.client.net.ServerConnection;
import vn.wisense.common.auth.Accounts;
import vn.wisense.common.auth.Auth;
import vn.wisense.common.protocol.Msg;

/**
 * Cửa sổ quản trị, chỉ mở được với tài khoản admin. Mọi thao tác là lệnh TCP gửi lên server
 * (LIST_SENSORS, SET_SENSOR, SET_CONFIG, NEW_SENSOR_KEY, LIST_USERS, SAVE_USER, DELETE_USER, LIST_SESSIONS, KICK);
 * server kiểm tra quyền lần nữa và trả OK/ERROR theo req. MainView chuyển các câu trả lời đó sang đây.
 *
 * <p>Tài khoản mới: mật khẩu được băm (PBKDF2) ngay trên máy admin, chỉ salt và key đi trên mạng.
 */
public class AdminView {
    /** req của lệnh quản trị bắt đầu từ đây để MainView biết câu trả lời nào thuộc cửa sổ này. */
    public static final long REQ_BASE = 1_000_000;

    private final ServerConnection conn;
    private final Stage stage = new Stage();
    private final TableView<Message.SensorInfo> sensors = new TableView<>();
    private final TableView<Message.UserInfo> users = new TableView<>();
    private final TableView<Message.SessionInfo> sessions = new TableView<>();
    private final Label status = new Label("Sẵn sàng");
    /** Lệnh đang chờ: req → việc cần làm khi OK (báo kết quả, làm mới bảng). */
    private final Map<Long, Runnable> onOk = new HashMap<>();
    private long nextReq = REQ_BASE;

    public AdminView(ServerConnection conn, String user) {
        this.conn = conn;
        TabPane tabs = new TabPane(
                new Tab("Cảm biến", sensorTab()),
                new Tab("Tài khoản", userTab()),
                new Tab("Kết nối", sessionTab()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> refresh(b.intValue()));
        BorderPane root = new BorderPane(tabs);
        status.setPadding(new Insets(6, 10, 6, 10));
        root.setBottom(status);
        stage.setTitle("WiSense: quản trị (" + user + ")");
        stage.setScene(new Scene(root, 760, 440));
    }

    public void show() {
        stage.show();
        stage.toFront();
        refresh(0);
        refresh(1);
        refresh(2);
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    public void close() {
        stage.close();
    }

    /** Tin dành cho cửa sổ này (MainView gọi trên luồng JavaFX). */
    public void handle(Message m) {
        switch (m) {
            case Message.Sensors s -> sensors.getItems().setAll(s.sensors());
            case Message.Users u -> users.getItems().setAll(u.users());
            case Message.Sessions s -> sessions.getItems().setAll(s.sessions());
            case Message.SensorKey k -> {
                ok("Đã tạo khoá mới cho cảm biến " + k.sensor());
                showKey(k);
                send(Msg.request(Msg.LIST_SENSORS, nextReq++));
            }
            case Message.Ok ok -> {
                Runnable r = onOk.remove(ok.req());
                if (r != null) r.run();
            }
            case Message.ServerError e -> {
                onOk.remove(e.req());
                error(describe(e));
            }
            case Message.SensorStatus st -> send(Msg.request(Msg.LIST_SENSORS, nextReq++));
            default -> { }
        }
    }

    // ------------------------------------------------------------ tab Cảm biến

    private BorderPane sensorTab() {
        sensors.getColumns().setAll(List.of(
                column("Mã", s -> Integer.toString(s.sensor())),
                column("Trạng thái", s -> s.online() ? "● online" : "○ offline"),
                column("Kết nối", s -> s.tcp() ? "TCP" : "—"),
                column("Khoá", s -> s.key() ? "đã có" : "chưa có")));
        sensors.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        Button refresh = new Button("Làm mới");
        refresh.setOnAction(e -> refresh(0));
        Button rate = new Button("Đổi tần số…");
        rate.setOnAction(e -> selectedSensor().ifPresent(this::askRate));
        Button motion = new Button("Ngưỡng chuyển động…");
        motion.setOnAction(e -> selectedSensor().ifPresent(id -> askThreshold(id, "motionThr",
                "Ngưỡng chuyển động (CV²); 0 = tự động")));
        Button breath = new Button("Ngưỡng SNR nhịp thở…");
        breath.setOnAction(e -> selectedSensor().ifPresent(id -> askThreshold(id, "breathMinSnr",
                "SNR tối thiểu để báo nhịp thở")));
        Button newKey = new Button("Tạo khoá mới");
        newKey.setOnAction(e -> selectedSensor().ifPresent(id -> confirm(
                "Tạo khoá mới cho cảm biến " + id + "? Khoá cũ hết hiệu lực, cảm biến đang nối sẽ bị ngắt.",
                () -> send(Msg.request(Msg.NEW_SENSOR_KEY, nextReq++, "sensor", id)))));
        Button add = new Button("Thêm cảm biến…");
        add.setOnAction(e -> askInt("Thêm cảm biến", "Mã cảm biến mới (0..255):", "")
                .ifPresent(id -> send(Msg.request(Msg.NEW_SENSOR_KEY, nextReq++, "sensor", id))));
        Button delKey = new Button("Xoá khoá");
        delKey.setOnAction(e -> selectedSensor().ifPresent(id -> confirm(
                "Xoá khoá cảm biến " + id + "? Cảm biến này sẽ không đăng nhập được nữa.",
                () -> request(Msg.request(Msg.DELETE_SENSOR_KEY, nextReq, "sensor", id),
                        "Đã xoá khoá cảm biến " + id, 0))));
        return tab(sensors, refresh, rate, motion, breath, add, newKey, delKey);
    }

    private Optional<Integer> selectedSensor() {
        Message.SensorInfo s = sensors.getSelectionModel().getSelectedItem();
        if (s == null) error("Chọn một cảm biến trong bảng trước");
        return Optional.ofNullable(s).map(Message.SensorInfo::sensor);
    }

    private void askRate(int sensor) {
        askInt("Đổi tần số lấy mẫu", "Cảm biến " + sensor + ": số lần ping router mỗi giây (1..200):", "100")
                .ifPresent(hz -> request(Msg.setSensor(nextReq, sensor, hz),
                        "Cảm biến " + sensor + " đã áp dụng " + hz + " lần/giây", -1));
    }

    private void askThreshold(int sensor, String key, String label) {
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Đổi ngưỡng");
        d.setHeaderText("Cảm biến " + sensor + ": " + key);
        d.setContentText(label + ":");
        d.showAndWait().ifPresent(text -> {
            try {
                double v = Double.parseDouble(text.trim().replace(',', '.'));
                request(Msg.withReq(Msg.setConfig(sensor, key, v), nextReq), "Đã đặt " + key + " = " + v, -1);
            } catch (NumberFormatException ex) {
                error("Giá trị phải là số");
            }
        });
    }

    private void showKey(Message.SensorKey k) {
        TextField field = new TextField(k.secret());
        field.setEditable(false);
        field.setPrefColumnCount(36);
        Button copy = new Button("Sao chép");
        copy.setOnAction(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(k.secret());
            Clipboard.getSystemClipboard().setContent(c);
        });
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Khoá cảm biến " + k.sensor());
        a.setHeaderText("Nạp khoá này vào ESP32 (menuconfig → WS_SENSOR_KEY) hoặc ReplayMain --key=...\n"
                + "Khoá chỉ hiện một lần.");
        a.getDialogPane().setContent(new VBox(8, field, copy));
        a.show();
    }

    // ------------------------------------------------------------ tab Tài khoản

    private BorderPane userTab() {
        users.getColumns().setAll(List.of(
                column("Tài khoản", Message.UserInfo::user),
                column("Quyền", u -> Msg.ACCESS_ADMIN.equals(u.access()) ? "admin (xem + điều khiển)" : "viewer (chỉ xem)"),
                column("Cảm biến được xem", u -> "*".equals(u.sensors()) ? "tất cả" : u.sensors().replace(';', ','))));
        users.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        Button refresh = new Button("Làm mới");
        refresh.setOnAction(e -> refresh(1));
        Button add = new Button("Thêm…");
        add.setOnAction(e -> editUser(null));
        Button edit = new Button("Sửa / đổi mật khẩu…");
        edit.setOnAction(e -> {
            Message.UserInfo u = users.getSelectionModel().getSelectedItem();
            if (u == null) error("Chọn một tài khoản trong bảng trước");
            else editUser(u);
        });
        Button delete = new Button("Xoá");
        delete.setOnAction(e -> {
            Message.UserInfo u = users.getSelectionModel().getSelectedItem();
            if (u == null) {
                error("Chọn một tài khoản trong bảng trước");
                return;
            }
            confirm("Xoá tài khoản " + u.user() + "? Người này đang đăng nhập sẽ bị ngắt.",
                    () -> request(Msg.deleteUser(nextReq, u.user()), "Đã xoá tài khoản " + u.user(), 1));
        });
        return tab(users, refresh, add, edit, delete);
    }

    /** Hộp thoại thêm (old = null) hoặc sửa tài khoản; băm mật khẩu trên luồng nền rồi gửi SAVE_USER. */
    private void editUser(Message.UserInfo old) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(old == null ? "Thêm tài khoản" : "Sửa tài khoản " + old.user());
        TextField name = new TextField(old == null ? "" : old.user());
        name.setDisable(old != null);
        ComboBox<String> access = new ComboBox<>();
        access.getItems().setAll(Msg.ACCESS_VIEWER, Msg.ACCESS_ADMIN);
        access.setValue(old == null ? Msg.ACCESS_VIEWER : old.access());
        TextField see = new TextField(old == null ? "*" : old.sensors().replace(';', ','));
        PasswordField pass = new PasswordField();
        PasswordField again = new PasswordField();
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(8);
        g.addRow(0, new Label("Tài khoản"), name);
        g.addRow(1, new Label("Quyền"), access);
        g.addRow(2, new Label("Cảm biến được xem"), see);
        g.addRow(3, new Label(old == null ? "Mật khẩu" : "Mật khẩu mới"), pass);
        g.addRow(4, new Label("Nhập lại"), again);
        g.add(new Label("Cảm biến: * là tất cả, hoặc ví dụ 1,2. Mật khẩu ít nhất 6 ký tự."), 0, 5, 2, 1);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        if (d.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        String user = name.getText().trim();
        Set<Integer> sensorSet;
        try {
            sensorSet = Accounts.parseSensors(see.getText().trim().replace(',', ';').replace(" ", ""));
        } catch (RuntimeException ex) {
            error("Danh sách cảm biến không hợp lệ: dùng * hoặc 1,2");
            return;
        }
        if (!pass.getText().equals(again.getText())) {
            error("Hai lần nhập mật khẩu không khớp");
            return;
        }
        if (pass.getText().length() < 6) {
            error("Mật khẩu phải có ít nhất 6 ký tự");
            return;
        }
        char[] password = pass.getText().toCharArray();
        String level = access.getValue();
        long req = nextReq++;
        onOk.put(req, () -> {
            ok("Đã lưu tài khoản " + user);
            refresh(1);
        });
        status.setTextFill(Color.GRAY);
        status.setText("Đang băm mật khẩu…");
        Thread t = new Thread(() -> {                       // PBKDF2 mất vài trăm ms: không khoá giao diện
            byte[] salt = Auth.randomBytes(Auth.SALT_BYTES);
            byte[] key = Auth.deriveKey(password, salt, Auth.DEFAULT_ITERATIONS);
            java.util.Arrays.fill(password, '\0');
            conn.request(Msg.saveUser(req, user, level, sensorSet, Auth.hex(salt), Auth.DEFAULT_ITERATIONS,
                    Auth.hex(key)));
            Platform.runLater(() -> status.setText("Đã gửi, chờ server…"));
        }, "hash-password");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ tab Kết nối

    private BorderPane sessionTab() {
        sessions.getColumns().setAll(List.of(
                column("Phiên", s -> s.session() + (s.self() ? " (bạn)" : "")),
                column("Vai trò", s -> switch (s.role()) {
                    case "sensor" -> "cảm biến";
                    case "monitor" -> "giám sát";
                    default -> "chưa đăng nhập";
                }),
                column("Tên", Message.SessionInfo::name),
                column("Tài khoản", Message.SessionInfo::user),
                column("Địa chỉ", Message.SessionInfo::address),
                column("Trạng thái", Message.SessionInfo::state)));
        sessions.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        Button refresh = new Button("Làm mới");
        refresh.setOnAction(e -> refresh(2));
        Button kick = new Button("Ngắt kết nối");
        kick.setOnAction(e -> {
            Message.SessionInfo s = sessions.getSelectionModel().getSelectedItem();
            if (s == null) {
                error("Chọn một phiên trong bảng trước");
                return;
            }
            confirm("Ngắt phiên " + s.session() + " (" + s.name() + ", " + s.address() + ")?",
                    () -> request(Msg.request(Msg.KICK, nextReq, "session", s.session()),
                            "Đã ngắt phiên " + s.session(), 2));
        });
        return tab(sessions, refresh, kick);
    }

    // ------------------------------------------------------------ chung

    private void refresh(int tab) {
        switch (tab) {
            case 0 -> send(Msg.request(Msg.LIST_SENSORS, nextReq++));
            case 1 -> send(Msg.request(Msg.LIST_USERS, nextReq++));
            case 2 -> send(Msg.request(Msg.LIST_SESSIONS, nextReq++));
            default -> { }
        }
    }

    /** Gửi lệnh có req = nextReq; OK thì báo done và làm mới tab refreshTab (-1: không làm mới). */
    private void request(String json, String done, int refreshTab) {
        long req = nextReq++;
        onOk.put(req, () -> {
            ok(done);
            if (refreshTab >= 0) refresh(refreshTab);
        });
        send(json);
    }

    private void send(String json) {
        conn.request(json);
    }

    private static <T> TableColumn<T, String> column(String title, Function<T, String> value) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(value.apply(cd.getValue())));
        return c;
    }

    private static BorderPane tab(TableView<?> table, Button... buttons) {
        FlowPane bar = new FlowPane(8, 8, buttons);
        bar.setPadding(new Insets(8));
        BorderPane p = new BorderPane(table);
        p.setTop(bar);
        return p;
    }

    private static Optional<Integer> askInt(String title, String label, String initial) {
        TextInputDialog d = new TextInputDialog(initial);
        d.setTitle(title);
        d.setHeaderText(null);
        d.setContentText(label);
        return d.showAndWait().flatMap(t -> {
            try {
                return Optional.of(Integer.parseInt(t.trim()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    private static void confirm(String text, Runnable action) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        if (a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) action.run();
    }

    private static String describe(Message.ServerError e) {
        return switch (e.code()) {
            case Msg.FORBIDDEN -> "Không có quyền: " + e.msg();
            case Msg.SENSOR_OFFLINE -> "Cảm biến không nối TCP nên không đổi tần số được";
            case Msg.TIMEOUT -> "Cảm biến không trả lời trong 3 giây";
            default -> e.code() + ": " + e.msg();
        };
    }

    private void ok(String text) {
        status.setTextFill(Color.SEAGREEN);
        status.setText(text);
    }

    private void error(String text) {
        status.setTextFill(Color.CRIMSON);
        status.setText(text);
    }
}
