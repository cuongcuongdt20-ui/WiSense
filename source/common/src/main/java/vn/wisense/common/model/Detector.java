package vn.wisense.common.model;

import java.util.List;

/** Giao diện giữa server (TV2) và các bộ phát hiện (TV3, TV4). */
public interface Detector {
    void onFrame(CsiFrame f);

    /** Trả về các sự kiện mới kể từ lần gọi trước và xóa chúng khỏi hàng đợi. */
    List<Event> poll();

    /** Optional live configuration hook; implementations validate supported keys and ranges. */
    default void configure(String key, double value) {
        throw new IllegalArgumentException("Unsupported detector configuration: " + key);
    }
}
