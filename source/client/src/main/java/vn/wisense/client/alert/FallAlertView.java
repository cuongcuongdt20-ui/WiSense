package vn.wisense.client.alert;

import java.awt.Toolkit;
import java.time.ZoneId;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.util.Duration;
import vn.wisense.client.net.Message;

/**
 * Cảnh báo té ngã trên client (TV4): popup không chặn màn hình, tiếng bíp và danh sách các lần té ngã gần đây.
 *
 * <p>Chỉ lo hiển thị. ACK và lọc FALL trùng id do ServerConnection làm, nên mỗi lần {@link #onFall} là một
 * cảnh báo mới. {@code onFall} luôn được gọi trên luồng JavaFX; nếu lỡ gọi từ luồng khác thì tự chuyển sang luồng JavaFX.
 */
public class FallAlertView implements FallAlertHandler {

    /** Số dòng giữ lại trong danh sách. */
    static final int MAX_HISTORY = 50;
    private static final int MAX_POPUPS = 4;
    private static final double POPUP_WIDTH = 330;
    private static final double POPUP_HEIGHT = 92;
    private static final Duration POPUP_TIME = Duration.seconds(10);

    private final Window owner;
    private final Runnable sound;
    private final ObservableList<String> items = FXCollections.observableArrayList();
    private final VBox panel = new VBox(4);
    private final Popup[] slots = new Popup[MAX_POPUPS];
    private final PauseTransition[] timers = new PauseTransition[MAX_POPUPS];
    
    /** Thứ tự mở của từng ô (số càng nhỏ càng cũ). Dùng bộ đếm thay vì giờ để không bao giờ trùng nhau. */
    private final long[] openedSeq = new long[MAX_POPUPS];
    private long popupCounter;

    /** @param owner cửa sổ chính; popup hiện ở góc trên bên phải cửa sổ này. */
    public FallAlertView(Window owner) {
        this(owner, FallAlertView::beep);
    }

    FallAlertView(Window owner, Runnable sound) {
        this.owner = owner;
        this.sound = sound;

        ListView<String> list = new ListView<>(items);
        list.setPlaceholder(new Label("Chưa có cảnh báo té ngã"));
        list.setPrefHeight(120);
        Label title = new Label("Té ngã gần đây");
        title.setStyle("-fx-font-weight: bold;");
        panel.setPadding(new Insets(6, 8, 8, 8));
        panel.getChildren().addAll(title, list);
    }

    /** Khung danh sách té ngã gần đây, để đặt vào giao diện chính. */
    public Node node() {
        return panel;
    }

    @Override
    public void onFall(Message.Fall fall) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> onFall(fall));
            return;
        }
        String line = FallFormat.line(fall.sensor(), fall.time(), fall.confidence(), ZoneId.systemDefault());
        items.add(0, line);
        while (items.size() > MAX_HISTORY) items.remove(items.size() - 1);
        sound.run();
        showPopup(fall, line);
    }

    // ------------------------------------------------------------ popup

    private void showPopup(Message.Fall fall, String line) {
        if (owner == null || !owner.isShowing()) return;      // chưa có cửa sổ: vẫn có dòng trong danh sách

        int slot = freeSlot();
        if (slot < 0) {                                       // đủ 4 popup: đóng cái mở sớm nhất để lấy chỗ
            slot = oldestSlot();
            closeSlot(slot);
        }

        Label head = new Label("⚠ PHÁT HIỆN TÉ NGÃ");
        head.setStyle("-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: bold;");
        Label detail = new Label("Cảm biến " + fall.sensor() + " · tin cậy " + FallFormat.percent(fall.confidence()));
        detail.setStyle("-fx-text-fill: white;");
        Label when = new Label(line.substring(0, line.indexOf(" · ")));
        when.setStyle("-fx-text-fill: #ffcdd2;");
        VBox text = new VBox(2, head, detail, when);

        final int s = slot;
        Button close = new Button("Đóng");
        close.setOnAction(e -> closeSlot(s));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(8, text, spacer, close);
        box.setPadding(new Insets(10, 12, 10, 12));
        box.setPrefSize(POPUP_WIDTH, POPUP_HEIGHT);
        box.setStyle("-fx-background-color: #c62828; -fx-background-radius: 8; "
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 12, 0.2, 0, 3);");

        Popup popup = new Popup();
        popup.setAutoHide(false);                             // không tự đóng khi bấm ra ngoài, không chặn thao tác
        popup.getContent().add(box);
        slots[s] = popup;
        openedSeq[s] = ++popupCounter;
        double x = owner.getX() + owner.getWidth() - POPUP_WIDTH - 24;
        double y = owner.getY() + 48 + s * (POPUP_HEIGHT + 10);
        popup.show(owner, x, y);

        PauseTransition timer = new PauseTransition(POPUP_TIME);
        timer.setOnFinished(e -> closeSlot(s));
        timers[s] = timer;
        timer.play();
    }

    private int freeSlot() {
        for (int i = 0; i < slots.length; i++) if (slots[i] == null) return i;
        return -1;
    }

    private void closeSlot(int i) {
        if (timers[i] != null) timers[i].stop();
        timers[i] = null;
        if (slots[i] != null) slots[i].hide();
        slots[i] = null;
    }

    /** Ô đang mở có thứ tự mở nhỏ nhất, tức popup cũ nhất. Chỉ gọi khi mọi ô đều đang mở. */
    private int oldestSlot() {
        int oldest = 0;
        for (int i = 1; i < slots.length; i++) if (openedSeq[i] < openedSeq[oldest]) oldest = i;
        return oldest;
    }

    private static void beep() {
        try {
            Toolkit.getDefaultToolkit().beep();
        } catch (Throwable ignored) {
            // máy không có âm thanh hoặc chạy không màn hình: bỏ qua, cảnh báo vẫn hiện
        }
    }

    // test
    int historySize() {
        return items.size();
    }

    int openPopups() {
        int n = 0;
        for (Popup p : slots) if (p != null) n++;
        return n;
    }
}
