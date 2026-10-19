package vn.wisense.server.tcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FallDeliveryTest {
    private static final String FALL_1 = "{\"t\":\"FALL\",\"sensor\":1,\"id\":1}";

    /** Phiên giả: ghi lại các tin được gửi; full = hàng đợi đầy. */
    private static final class FakeSession implements FallDelivery.Recipient {
        final String name;
        final Set<Integer> sensors;
        final List<String> sent = new ArrayList<>();
        boolean full;

        FakeSession(String name, Integer... sensors) {
            this.name = name;
            this.sensors = Set.of(sensors);
        }

        public String name() {
            return name;
        }

        public boolean wants(int sensor) {
            return sensors.contains(sensor);
        }

        public boolean send(String json) {
            if (full) return false;
            sent.add(json);
            return true;
        }
    }

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<FakeSession> sessions = new CopyOnWriteArrayList<>();
    private final FallDelivery fd = new FallDelivery(() -> sessions, now::get);

    private void advance(long ms) {
        now.addAndGet(ms);
        fd.tick();
    }

    @Test
    void chiGuiChoPhienDaDangKySensor() {
        FakeSession a = new FakeSession("a", 1);
        FakeSession b = new FakeSession("b", 2);
        FakeSession notHello = new FakeSession(null, 1);
        sessions.addAll(List.of(a, b, notHello));

        fd.onFall(1, 1, FALL_1);

        assertEquals(List.of(FALL_1), a.sent);
        assertEquals(List.of(), b.sent);
        assertEquals(List.of(), notHello.sent);
        assertEquals(1, fd.pendingCount("a"));
        assertEquals(0, fd.pendingCount("b"));
    }

    @Test
    void ackThiHetChoVaKhongGuiLai() {
        FakeSession a = new FakeSession("a", 1);
        sessions.add(a);
        fd.onFall(1, 1, FALL_1);

        fd.ack("a", 1);
        fd.ack("a", 1);                                    // ACK trùng không lỗi
        fd.ack("ai-do", 99);                               // ACK lạ không lỗi
        advance(10_000);

        assertEquals(0, fd.pendingCount("a"));
        assertEquals(1, a.sent.size());
    }

    @Test
    void khongAckThiGuiLaiSau3GiayToiDa5Lan() {
        FakeSession a = new FakeSession("a", 1);
        sessions.add(a);
        fd.onFall(1, 1, FALL_1);                           // lần 1

        advance(2_000);
        assertEquals(1, a.sent.size());                    // chưa đủ 3 giây
        advance(1_000);
        assertEquals(2, a.sent.size());                    // lần 2
        for (int i = 0; i < 10; i++) advance(FallDelivery.RESEND_AFTER_MS);

        assertEquals(FallDelivery.MAX_TRIES, a.sent.size());
        assertEquals(0, fd.pendingCount("a"));             // đã bỏ sau lần cuối
    }

    @Test
    void roiMangRoiNoiLaiCungTenThiNhanLai() {
        FakeSession old = new FakeSession("ui-1", 1);
        sessions.add(old);
        old.full = true;                                   // gửi không được vì client đang nghẽn rồi rớt
        fd.onFall(1, 1, FALL_1);
        sessions.remove(old);
        advance(4_000);                                    // không có ai nhận, vẫn giữ
        assertEquals(1, fd.pendingCount("ui-1"));

        FakeSession reconnected = new FakeSession("ui-1", 1);
        sessions.add(reconnected);
        fd.onHello(reconnected);

        assertEquals(List.of(FALL_1), reconnected.sent);
        fd.ack("ui-1", 1);
        assertEquals(0, fd.pendingCount("ui-1"));
    }

    @Test
    void hangDoiDayKhongTinhLaMotLanGui() {
        FakeSession a = new FakeSession("a", 1);
        sessions.add(a);
        a.full = true;
        fd.onFall(1, 1, FALL_1);
        advance(3_000);
        advance(3_000);
        assertEquals(1, fd.pendingCount("a"));

        a.full = false;
        advance(3_000);
        assertEquals(1, a.sent.size());
    }

    @Test
    void clientKhongQuayLaiSau5PhutThiBo() {
        FakeSession a = new FakeSession("a", 1);
        sessions.add(a);
        fd.onFall(1, 1, FALL_1);
        sessions.clear();

        advance(FallDelivery.MAX_AGE_MS + 1);

        assertEquals(0, fd.pendingCount("a"));
    }
}
