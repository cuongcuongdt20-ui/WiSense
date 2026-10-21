package vn.wisense.client.alert;

import vn.wisense.client.net.Message;

/** Chỗ nối với phần cảnh báo của TV4 (popup, âm thanh, lịch sử té ngã). */
@FunctionalInterface
public interface FallAlertHandler {
    void onFall(Message.Fall fall);                          // luôn gọi trên luồng JavaFX
}
