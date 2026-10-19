package vn.wisense.client.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import vn.wisense.client.net.Message;

/** Trạng thái có người, mức chuyển động, nhịp thở và SNR của một cảm biến. */
public class StatusPanel {
    private final VBox box = new VBox(10);
    private final Label motion = new Label("Chưa có dữ liệu");
    private final ProgressBar level = new ProgressBar(0);
    private final Label bpm = new Label("Nhịp thở: —");
    private final Label snr = new Label();
    private long lastBreathMs;

    public StatusPanel() {
        motion.setStyle("-fx-font-size: 18; -fx-font-weight: bold;");
        bpm.setStyle("-fx-font-size: 22;");
        box.getChildren().addAll(new Label("Cảm biến 1"), motion, level, bpm, snr);
        box.setPadding(new Insets(12));
        box.setPrefWidth(240);
    }

    public Node node() {
        return box;
    }

    public void showMotion(Message.Motion m) {
        motion.setText(m.moving() ? "Có người di chuyển" : "Không có chuyển động");
        motion.setTextFill(m.moving() ? Color.SEAGREEN : Color.GRAY);
        level.setProgress(Math.min(1, m.level()));
    }

    public void showBreath(Message.Breath b) {
        bpm.setText(String.format("Nhịp thở: %.1f /phút", b.bpm()));
        snr.setText(String.format("SNR %.1f", b.snr()));
        lastBreathMs = System.currentTimeMillis();
    }

    public void tick() {                                     // tin nhịp thở cũ hơn 10 giây thì xoá
        if (lastBreathMs > 0 && System.currentTimeMillis() - lastBreathMs > 10_000) {
            bpm.setText("Nhịp thở: —");
            snr.setText("");
            lastBreathMs = 0;
        }
    }
}
