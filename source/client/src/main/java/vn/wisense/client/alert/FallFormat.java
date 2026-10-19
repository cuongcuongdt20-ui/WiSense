package vn.wisense.client.alert;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Định dạng một dòng cảnh báo té ngã. Tách riêng, không dùng JavaFX, để test được khi không có màn hình. */
public final class FallFormat {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");

    private FallFormat() {}

    /** Ví dụ: "14:03:21 20/10/2026 · cảm biến 1 · tin cậy 80%". {@code timeSec} là giây theo epoch. */
    public static String line(int sensor, long timeSec, double confidence, ZoneId zone) {
        String when = TIME.format(Instant.ofEpochSecond(timeSec).atZone(zone));
        return when + " · cảm biến " + sensor + " · tin cậy " + percent(confidence);
    }

    /** Độ tin cậy 0–1 thành "80%"; ngoài khoảng thì cắt về 0–100%. */
    public static String percent(double confidence) {
        long p = Math.round(confidence * 100);
        return Math.max(0, Math.min(100, p)) + "%";
    }
}