package vn.wisense.detect.motion;

import org.junit.jupiter.api.Test;
import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.model.Event;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MotionDetectorTest {

    private static final long STEP = 10_000L;     // 100 gói/giây

    /** Phòng yên: nền base + nhiễu ±2,5% của base. */
    private static CsiFrame calm(int sensor, long i, int len, float base, Random rnd, long stepUs) {
        float[] a = new float[len];
        for (int k = 0; k < len; k++) a[k] = base + (rnd.nextFloat() - 0.5f) * (base / 20f);
        return new CsiFrame(sensor, i, i * stepUs, -50, a, new float[len]);
    }

    private static CsiFrame calm(int sensor, long i, float base, Random rnd) {
        return calm(sensor, i, 64, base, rnd, STEP);
    }

    /** Có chuyển động: biên độ lắc ±10% quanh base. */
    private static CsiFrame shake(int sensor, long i, float base) {
        float[] a = new float[64];
        Arrays.fill(a, i % 2 == 0 ? base * 0.9f : base * 1.1f);
        return new CsiFrame(sensor, i, i * STEP, -50, a, new float[64]);
    }

    private static boolean moving(Event e) { return (Boolean) e.fields().get("moving"); }

    private static Event last(List<Event> evs, int sensor) {
        Event r = null;
        for (Event e : evs) if (e.sensorId() == sensor) r = e;
        return r;
    }

    /** 6 giây phòng yên (khung 0..599): đủ 5 lần học nền, lần đánh giá thứ 6 ở khung 600. */
    private static void warmup(MotionDetector d, Random rnd, float base) {
        for (long i = 0; i < 600; i++) d.onFrame(calm(1, i, base, rnd));
    }

    @Test
    void warmupThenNoiseIsNotMotion() {
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);
        for (long i = 0; i < 750; i++) d.onFrame(calm(1, i, 20f, rnd));
        List<Event> evs = d.poll();
        assertEquals(7, evs.size());                         // đánh giá ở khung 100, 200, ..., 700
        for (int j = 0; j < 5; j++) assertEquals(0.0, evs.get(j).num("level"), 1e-9);   // đang học nền
        for (Event e : evs) assertFalse(moving(e));          // chỉ có nhiễu thì không được báo
        assertTrue(evs.get(6).num("level") < 0.2);
        assertEquals(0, d.poll().size());                    // poll xong thì hàng đợi rỗng
    }

    @Test
    void shakingSignalIsMoving() {
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);
        warmup(d, rnd, 20f);
        for (long i = 600; i <= 700; i++) d.onFrame(shake(1, i, 20f));
        Event e = last(d.poll(), 1);
        assertTrue(moving(e));
        assertEquals(1.0, e.num("level"), 1e-9);
    }

    @Test
    void movingStopsWhenRoomGoesCalm() {
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);
        warmup(d, rnd, 20f);
        for (long i = 600; i < 700; i++) d.onFrame(shake(1, i, 20f));
        for (long i = 700; i <= 800; i++) d.onFrame(calm(1, i, 20f, rnd));
        List<Event> evs = d.poll();
        assertEquals(8, evs.size());                         // 5 học nền + khung 600, 700, 800
        assertTrue(moving(evs.get(6)));
        assertFalse(moving(evs.get(7)));
    }

    @Test
    void bigAmplitudeCalmRoomIsNotMotion() {
        // lỗi reviewer tìm ra: biên độ ×4 thì phương sai ×16, ngưỡng tuyệt đối báo nhầm cả phòng trống
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);
        for (long i = 0; i < 3100; i++) d.onFrame(calm(1, i, 80f, rnd));
        List<Event> evs = d.poll();
        assertEquals(30, evs.size());
        for (Event e : evs) assertFalse(moving(e));
    }

    @Test
    void bigAmplitudeShakingIsStillMoving() {
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);
        warmup(d, rnd, 80f);
        for (long i = 600; i <= 700; i++) d.onFrame(shake(1, i, 80f));
        assertTrue(moving(last(d.poll(), 1)));
    }

    @Test
    void sensorsAreIndependent() {
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);
        for (long i = 0; i < 600; i++) {
            d.onFrame(calm(1, i, 20f, rnd));
            d.onFrame(calm(2, i, 20f, rnd));
        }
        for (long i = 600; i <= 700; i++) {
            d.onFrame(shake(1, i, 20f));                     // cảm biến 1: lắc mạnh
            d.onFrame(calm(2, i, 20f, rnd));                 // cảm biến 2: yên
        }
        List<Event> evs = d.poll();
        assertTrue(moving(last(evs, 1)));
        assertFalse(moving(last(evs, 2)));                   // trộn dữ liệu giữa 2 cảm biến thì test này fail
    }

    @Test
    void changingSubcarrierCountResetsWindow() {
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);
        for (long i = 0; i < 50; i++) d.onFrame(calm(1, i, 64, 20f, rnd, STEP));
        for (long i = 50; i <= 150; i++) d.onFrame(calm(1, i, 32, 20f, rnd, STEP));   // không được văng exception
        assertEquals(1, d.poll().size());                    // chỉ tính trên 1 giây khung 32 sóng mang con
    }

    @Test
    void lowPacketRateStillEmitsOncePerSecond() {
        // lỗi reviewer tìm ra: Wi-Fi thật chỉ ~40 gói/giây, đếm 100 khung thì 2,5 giây mới có 1 tin
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);
        for (long i = 0; i < 300; i++) d.onFrame(calm(1, i, 64, 20f, rnd, 25_000L));   // 7,5 giây
        assertEquals(7, d.poll().size());                    // khung 40, 80, ..., 280 (cứ 1 giây một tin)
    }

    @Test
    void tooFewFramesInWindowGivesNoEvent() {
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);
        for (long i = 0; i < 100; i++) d.onFrame(calm(1, i, 64, 20f, rnd, 200_000L)); // 5 gói/giây, 20 giây
        assertEquals(0, d.poll().size());                    // cửa sổ 1 giây chỉ có ~6 khung < minFrames
    }

    @Test
    void flatSignalHasZeroVariance() {
        List<float[]> w = new ArrayList<>();
        for (int i = 0; i < 4; i++) w.add(new float[]{20, 20});
        assertEquals(0.0, MotionDetector.meanNormVariance(w), 1e-9);
    }

    @Test
    void alternatingSignalHasNormVarianceOnePercent() {
        List<float[]> w = new ArrayList<>();
        for (float v : new float[]{18, 22, 18, 22}) w.add(new float[]{v, v});
        assertEquals(0.01, MotionDetector.meanNormVariance(w), 1e-9);    // phương sai 4 / 20²
    }

    @Test
    void nullSubcarrierIsIgnored() {
        List<float[]> w = new ArrayList<>();
        for (float v : new float[]{18, 22, 18, 22}) w.add(new float[]{v, 0});
        assertEquals(0.01, MotionDetector.meanNormVariance(w), 1e-9);
    }

    @Test
    void scaleDoesNotChangeNormVariance() {
        List<float[]> a = new ArrayList<>();
        List<float[]> b = new ArrayList<>();
        for (float v : new float[]{18, 22, 18, 22}) {
            a.add(new float[]{v, v});
            b.add(new float[]{4 * v, 4 * v});
        }
        assertEquals(MotionDetector.meanNormVariance(a), MotionDetector.meanNormVariance(b), 1e-9);
    }


    @Test
    void sequenceResetClearsOldWindowAndRelearnsBaseline() {
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);

        // Học nền ở phiên chạy đầu.
        warmup(d, rnd, 20f);
        d.poll();

        // ESP32 khởi động lại: seq quay về 0, timestamp quay về 0.
        for (long i = 0; i < 600; i++) {
            d.onFrame(calm(1, i, 64, 20f, rnd, STEP));
        }

        List<Event> events = d.poll();

        // Không được dùng ngay trạng thái chuyển động/nền cũ.
        assertFalse(events.isEmpty());
        for (Event e : events) {
            assertFalse(moving(e));
        }
    }

    @Test
    void timestampResetClearsOldWindow() {
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);

        warmup(d, rnd, 20f);
        d.poll();

        // seq vẫn tăng nhưng timestamp quay lùi hơn 1 giây.
        for (long i = 600; i < 1200; i++) {
            long resetSeq = i;
            long resetTs = (i - 600) * STEP;
            float[] amp = new float[64];
            Arrays.fill(amp, 20f);

            d.onFrame(new CsiFrame(
                    1, resetSeq, resetTs, -50, amp, new float[64]));
        }

        List<Event> events = d.poll();
        assertFalse(events.isEmpty());

        for (Event e : events) {
            assertFalse(moving(e));
        }
    }

    @Test
    void configureMotionThresholdAcceptsPositiveFiniteValue() {
        MotionDetector d = new MotionDetector();

        assertDoesNotThrow(() -> d.configure("motionThr", 0.01));
    }

    @Test
    void configureMotionThresholdZeroResetsToAutomaticMode() {
        MotionDetector d = new MotionDetector();

        assertDoesNotThrow(() -> d.configure("motionThr", 0.01));
        assertDoesNotThrow(() -> d.configure("motionThr", 0.0));
    }


    @Test
    void configureMotionThresholdRejectsInvalidValues() {
        MotionDetector d = new MotionDetector();

        assertThrows(IllegalArgumentException.class,
                () -> d.configure("unknownKey", 0.01));

        assertThrows(IllegalArgumentException.class,
                () -> d.configure("motionThr", -1.0));

        assertThrows(IllegalArgumentException.class,
                () -> d.configure("motionThr", Double.NaN));

        assertThrows(IllegalArgumentException.class,
                () -> d.configure("motionThr", Double.POSITIVE_INFINITY));
    }

}
