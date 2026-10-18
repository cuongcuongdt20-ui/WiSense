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

    private static final int WARMUP = 500;      // 5 lần đánh giá × 100 khung

    /** Phòng yên: nền base + nhiễu ±2,5% của base. */
    private static CsiFrame calm(int sensor, long i, int len, float base, Random rnd) {
        float[] a = new float[len];
        for (int k = 0; k < len; k++) a[k] = base + (rnd.nextFloat() - 0.5f) * (base / 20f);
        return new CsiFrame(sensor, i, i * 10_000L, -50, a, new float[len]);
    }

    /** Có chuyển động: biên độ lắc ±10% quanh base. */
    private static CsiFrame shake(int sensor, long i, float base) {
        float[] a = new float[64];
        Arrays.fill(a, i % 2 == 0 ? base * 0.9f : base * 1.1f);
        return new CsiFrame(sensor, i, i * 10_000L, -50, a, new float[64]);
    }

    private static boolean moving(Event e) { return (Boolean) e.fields().get("moving"); }

    private static Event last(List<Event> evs, int sensor) {
        Event r = null;
        for (Event e : evs) if (e.sensorId() == sensor) r = e;
        return r;
    }

    @Test
    void warmupThenNoiseIsNotMotion() {
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);
        for (long i = 0; i < WARMUP + 250; i++) d.onFrame(calm(1, i, 64, 20f, rnd));
        List<Event> evs = d.poll();
        assertEquals(7, evs.size());                         // 750 khung = 7 lần đánh giá
        for (int j = 0; j < 5; j++) assertEquals(0.0, evs.get(j).num("level"), 1e-9);   // đang học nền
        for (Event e : evs) assertFalse(moving(e));          // chỉ có nhiễu thì không được báo
        assertTrue(evs.get(6).num("level") < 0.2);
        assertEquals(0, d.poll().size());                    // poll xong thì hàng đợi rỗng
    }

    @Test
    void shakingSignalIsMoving() {
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);
        for (long i = 0; i < WARMUP; i++) d.onFrame(calm(1, i, 64, 20f, rnd));
        for (long i = WARMUP; i < WARMUP + 100; i++) d.onFrame(shake(1, i, 20f));
        Event e = last(d.poll(), 1);
        assertTrue(moving(e));
        assertEquals(1.0, e.num("level"), 1e-9);
    }

    @Test
    void movingStopsWhenRoomGoesCalm() {
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);
        for (long i = 0; i < WARMUP; i++) d.onFrame(calm(1, i, 64, 20f, rnd));
        for (long i = WARMUP; i < WARMUP + 100; i++) d.onFrame(shake(1, i, 20f));
        for (long i = WARMUP + 100; i < WARMUP + 200; i++) d.onFrame(calm(1, i, 64, 20f, rnd));
        List<Event> evs = d.poll();
        assertEquals(7, evs.size());
        assertTrue(moving(evs.get(5)));
        assertFalse(moving(evs.get(6)));
    }

    @Test
    void bigAmplitudeCalmRoomIsNotMotion() {
        // lỗi reviewer tìm ra: biên độ ×4 thì phương sai ×16, ngưỡng tuyệt đối báo nhầm cả phòng trống
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);
        for (long i = 0; i < 3000; i++) d.onFrame(calm(1, i, 64, 80f, rnd));
        List<Event> evs = d.poll();
        assertEquals(30, evs.size());
        for (Event e : evs) assertFalse(moving(e));
    }

    @Test
    void bigAmplitudeShakingIsStillMoving() {
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);
        for (long i = 0; i < WARMUP; i++) d.onFrame(calm(1, i, 64, 80f, rnd));
        for (long i = WARMUP; i < WARMUP + 100; i++) d.onFrame(shake(1, i, 80f));
        assertTrue(moving(last(d.poll(), 1)));
    }

    @Test
    void sensorsAreIndependent() {
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);
        for (long i = 0; i < WARMUP; i++) {
            d.onFrame(calm(1, i, 64, 20f, rnd));
            d.onFrame(calm(2, i, 64, 20f, rnd));
        }
        for (long i = WARMUP; i < WARMUP + 100; i++) {
            d.onFrame(shake(1, i, 20f));                     // cảm biến 1: lắc mạnh
            d.onFrame(calm(2, i, 64, 20f, rnd));             // cảm biến 2: yên
        }
        List<Event> evs = d.poll();
        assertTrue(moving(last(evs, 1)));
        assertFalse(moving(last(evs, 2)));                   // trộn dữ liệu giữa 2 cảm biến thì test này fail
    }

    @Test
    void changingSubcarrierCountResetsWindow() {
        MotionDetector d = new MotionDetector();
        Random rnd = new Random(42);
        for (long i = 0; i < 50; i++) d.onFrame(calm(1, i, 64, 20f, rnd));
        for (long i = 50; i < 150; i++) d.onFrame(calm(1, i, 32, 20f, rnd));   // không được văng exception
        assertEquals(1, d.poll().size());                    // chỉ tính trên 100 khung 32 sóng mang con
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
}