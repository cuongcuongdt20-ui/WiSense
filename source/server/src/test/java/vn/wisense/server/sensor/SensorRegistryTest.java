package vn.wisense.server.sensor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import vn.wisense.common.protocol.Msg;

class SensorRegistryTest {
    /** Kết nối giả của cảm biến: ghi lại tin server gửi xuống. */
    private static final class FakeLink implements SensorRegistry.Link {
        final List<String> sent = new ArrayList<>();
        boolean open = true;

        public boolean send(String json) {
            if (!open) return false;
            sent.add(json);
            return true;
        }

        public boolean isOpen() {
            return open;
        }
    }

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<String> toMonitors = new ArrayList<>();
    private final SensorRegistry reg = new SensorRegistry(toMonitors::add, now::get);

    private static JsonObject parse(String json) {
        return Msg.parse(json);
    }

    @Test
    void registerMakesOnlineOnceAndRejectsDuplicateWhileOpen() {
        FakeLink a = new FakeLink();
        FakeLink b = new FakeLink();
        assertTrue(reg.register(1, a));
        reg.touch(1);
        reg.touch(1);
        assertEquals(List.of(Msg.sensorStatus(1, true)), toMonitors);   // chỉ báo online một lần
        assertFalse(reg.register(1, b));                                 // trùng mã khi kết nối cũ còn mở
        a.open = false;
        assertTrue(reg.register(1, b));                                  // kết nối cũ đã đóng: nhận kết nối mới
        assertEquals(List.of(1), reg.online());
    }

    @Test
    void silentSensorGoesOfflineAfterFiveSecondsAndBackOnline() {
        reg.touch(2);                                                    // cảm biến UDP, không có kết nối TCP
        now.addAndGet(SensorRegistry.OFFLINE_AFTER_MS);
        reg.tick();
        assertEquals(List.of(2), reg.online());                          // đúng 5 giây: chưa offline
        now.addAndGet(1);
        reg.tick();
        assertEquals(List.of(), reg.online());
        assertEquals(Msg.sensorStatus(2, false), toMonitors.get(1));
        reg.touch(2);
        assertEquals(Msg.sensorStatus(2, true), toMonitors.get(2));
        assertEquals(List.of(2), reg.known());
    }

    @Test
    void unregisterIsImmediateButIgnoresStaleLink() {
        FakeLink old = new FakeLink();
        FakeLink fresh = new FakeLink();
        reg.register(1, old);
        old.open = false;
        reg.register(1, fresh);
        reg.unregister(1, old);                                          // kết nối cũ đóng muộn: không được làm offline
        assertEquals(List.of(1), reg.online());
        reg.unregister(1, fresh);
        assertEquals(List.of(), reg.online());
        assertEquals(Msg.sensorStatus(1, false), toMonitors.get(toMonitors.size() - 1));
    }

    @Test
    void routeConfigSendsConfigAndAnswersMonitorWithItsReq() {
        FakeLink link = new FakeLink();
        reg.register(1, link);
        List<String> replies = new ArrayList<>();

        assertTrue(reg.routeConfig(1, 50, 5, replies::add));
        JsonObject config = parse(link.sent.get(0));
        assertEquals(Msg.CONFIG, Msg.type(config));
        assertEquals(50, config.get("pingHz").getAsInt());

        reg.onConfigOk(config.get("req").getAsLong(), 50);
        JsonObject ok = parse(replies.get(0));
        assertEquals(Msg.OK, Msg.type(ok));
        assertEquals(Msg.SET_SENSOR, ok.get("re").getAsString());
        assertEquals(5, ok.get("req").getAsLong());
        assertEquals(0, reg.pendingCount());

        reg.onConfigOk(config.get("req").getAsLong(), 50);               // trả lời trùng: bỏ qua
        assertEquals(1, replies.size());
    }

    @Test
    void routeConfigFailsWhenSensorHasNoOpenLink() {
        reg.touch(3);                                                    // có dữ liệu UDP nhưng không có kênh TCP
        assertFalse(reg.routeConfig(3, 50, 1, json -> { }));
        assertFalse(reg.routeConfig(9, 50, 1, json -> { }));             // chưa từng thấy
    }

    @Test
    void unansweredConfigTimesOutWithReq() {
        reg.register(1, new FakeLink());
        List<String> replies = new ArrayList<>();
        reg.routeConfig(1, 20, 8, replies::add);
        now.addAndGet(SensorRegistry.CONFIG_TIMEOUT_MS - 1);
        reg.tick();
        assertTrue(replies.isEmpty());
        now.addAndGet(1);
        reg.tick();
        JsonObject err = parse(replies.get(0));
        assertEquals(Msg.TIMEOUT, err.get("code").getAsString());
        assertEquals(8, err.get("req").getAsLong());
        assertEquals(0, reg.pendingCount());
    }
}
