package vn.wisense.client.ui;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import vn.wisense.client.net.Message;
import vn.wisense.client.net.ServerConnection;
import vn.wisense.common.auth.Credentials;
import vn.wisense.common.protocol.Msg;

/**
 * Màn hình đăng nhập: kết nối, làm bắt tay HELLO → CHALLENGE → AUTH trên luồng nền, nhận WELCOME thì
 * chuyển kết nối đang mở sang màn hình chính. Nhớ server, cổng và tài khoản cho lần sau; không nhớ mật khẩu.
 */
public class LoginView {
    /** Kết nối đã đăng nhập xong, kèm hàng đợi tin và WELCOME để màn hình chính dùng tiếp. */
    public record Session(ServerConnection connection, ConcurrentLinkedQueue<Message> inbox, Message.Welcome welcome) {}

    private static final long CONNECT_TIMEOUT_MS = 8000;
    private final Preferences prefs = Preferences.userNodeForPackage(LoginView.class);
    private final TextField hostField = new TextField(prefs.get("host", "127.0.0.1"));
    private final TextField portField = new TextField(prefs.get("port", "7000"));
    private final TextField userField = new TextField(prefs.get("user", ""));
    private final PasswordField passwordField = new PasswordField();
    private final Button loginButton = new Button("Đăng nhập");
    private final Label message = new Label();
    private final VBox root;
    private final String clientName;
    private final Consumer<Session> onLoggedIn;
    private ServerConnection pending;
    private ConcurrentLinkedQueue<Message> inbox;
    private volatile boolean reached;
    private long deadline;
    private final AnimationTimer poller = new AnimationTimer() {
        @Override
        public void handle(long now) {
            poll();
        }
    };

    public LoginView(String clientName, String notice, Consumer<Session> onLoggedIn) {
        this.clientName = clientName;
        this.onLoggedIn = onLoggedIn;
        Label title = new Label("WiSense");
        title.setFont(Font.font(null, FontWeight.BOLD, 22));
        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.addRow(0, new Label("Server"), hostField);
        form.addRow(1, new Label("Cổng"), portField);
        form.addRow(2, new Label("Tài khoản"), userField);
        form.addRow(3, new Label("Mật khẩu"), passwordField);
        portField.setPrefColumnCount(6);
        loginButton.setDefaultButton(true);
        loginButton.setOnAction(e -> submit());
        message.setWrapText(true);
        message.setMaxWidth(280);
        root = new VBox(14, title, form, message, loginButton);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(24));
        root.setMaxWidth(340);
        if (notice != null) showError(notice);
        if (!userField.getText().isEmpty()) passwordField.requestFocus();
    }

    public Parent root() {
        return root;
    }

    private void submit() {
        String host = hostField.getText().trim();
        String user = userField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            showError("Cổng không hợp lệ");
            return;
        }
        if (host.isEmpty() || user.isEmpty() || passwordField.getText().isEmpty()) {
            showError("Nhập đủ server, tài khoản và mật khẩu");
            return;
        }
        prefs.put("host", host);
        prefs.put("port", Integer.toString(port));
        prefs.put("user", user);
        char[] password = passwordField.getText().toCharArray();
        passwordField.clear();
        Credentials creds = Credentials.user(user, password);
        java.util.Arrays.fill(password, '\0');

        setBusy(true);
        message.setTextFill(Color.GRAY);
        message.setText("Đang kết nối...");
        inbox = new ConcurrentLinkedQueue<>();
        reached = false;
        pending = new ServerConnection(host, port, clientName, creds, inbox, ok -> { if (ok) reached = true; });
        pending.start();
        deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
        poller.start();
    }

    /** Chờ WELCOME hoặc lỗi đăng nhập; quá hạn mà chưa tới được server thì báo lỗi. */
    private void poll() {
        Message m;
        while ((m = inbox.poll()) != null) {
            if (m instanceof Message.Welcome w) {
                poller.stop();
                onLoggedIn.accept(new Session(pending, inbox, w));
                return;
            }
            if (m instanceof Message.ServerError e) {
                fail(describe(e));
                return;
            }
        }
        if (System.currentTimeMillis() > deadline)
            fail(reached ? "Server không trả lời đăng nhập" : "Không tới được server " + hostField.getText().trim());
    }

    private void fail(String text) {
        poller.stop();
        pending.stop();
        setBusy(false);
        showError(text);
    }

    private static String describe(Message.ServerError e) {
        return switch (e.code()) {
            case Msg.AUTH_FAILED -> "Sai tài khoản hoặc mật khẩu";
            case Msg.TOO_MANY_ATTEMPTS -> "Sai quá nhiều lần, thử lại sau 1 phút";
            case Msg.BAD_ARG -> "Server từ chối: " + e.msg();
            default -> e.code() + ": " + e.msg();
        };
    }

    private void setBusy(boolean busy) {
        loginButton.setDisable(busy);
        hostField.setDisable(busy);
        portField.setDisable(busy);
        userField.setDisable(busy);
        passwordField.setDisable(busy);
    }

    private void showError(String text) {
        message.setTextFill(Color.CRIMSON);
        message.setText(text);
    }
}
