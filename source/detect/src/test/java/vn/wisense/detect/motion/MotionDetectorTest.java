package vn.wisense.detect.motion;

import org.junit.jupiter.api.Test;
import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.model.Event;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionDetectorTest {

    private static CsiFrame frame(int sensor, long i) {
        return new CsiFrame(sensor, i, i * 10_000L, -50, new float[64], new float[64]);
    }

    @Test
    void flatSignalHasZeroVariance() {
        List<float[]> w = new ArrayList<>();
        for (int i = 0; i < 4; i++) w.add(new float[]{20, 20});
        assertEquals(0.0, MotionDetector.meanVariance(w), 1e-9);
    }

    @Test
    void alternatingSignalHasVarianceFour() {
        List<float[]> w = new ArrayList<>();
        w.add(new float[]{18, 18});
        w.add(new float[]{22, 22});
        w.add(new float[]{18, 18});
        w.add(new float[]{22, 22});
        assertEquals(4.0, MotionDetector.meanVariance(w), 1e-9);
    }

    @Test
    void nullSubcarrierIsIgnored() {
        List<float[]> w = new ArrayList<>();
        w.add(new float[]{18, 0});
        w.add(new float[]{22, 0});
        w.add(new float[]{18, 0});
        w.add(new float[]{22, 0});
        assertEquals(4.0, MotionDetector.meanVariance(w), 1e-9);   // cột 0 giá trị 0 không kéo trung bình xuống
    }

    /** Khung có mọi sóng mang con bằng cùng một giá trị v. */
    private static CsiFrame frameAmp(int sensor, long i, float v) {
        float[] a = new float[64];
        Arrays.fill(a, v);
        return new CsiFrame(sensor, i, i * 10_000L, -50, a, new float[64]);
    }

    private static boolean moving(Event e) { return (Boolean) e.fields().get("moving"); }

    @Test
    void flatRoomIsNotMoving() {
        MotionDetector d = new MotionDetector();
        for (long i = 0; i < 100; i++) d.onFrame(frameAmp(1, i, 20f));
        Event e = d.poll().get(0);
        assertEquals(0.0, e.num("level"), 1e-9);
        assertFalse(moving(e));
    }

    @Test
    void shakingSignalIsMoving() {
        MotionDetector d = new MotionDetector();
        for (long i = 0; i < 100; i++) d.onFrame(frameAmp(1, i, i % 2 == 0 ? 18f : 22f));
        Event e = d.poll().get(0);
        assertEquals(1.0, e.num("level"), 1e-9);     // phương sai 4 >> 2*0.5 nên level bị chặn ở 1
        assertTrue(moving(e));
    }

    @Test
    void movingStopsWhenRoomGoesCalm() {
        MotionDetector d = new MotionDetector();
        for (long i = 0; i < 100; i++) d.onFrame(frameAmp(1, i, i % 2 == 0 ? 18f : 22f));
        for (long i = 100; i < 200; i++) d.onFrame(frameAmp(1, i, 20f));
        List<Event> evs = d.poll();
        assertEquals(2, evs.size());
        assertTrue(moving(evs.get(0)));
        assertFalse(moving(evs.get(1)));
    }

    @Test
    void emitsOneEventPerSecond() {
        MotionDetector d = new MotionDetector();
        for (long i = 0; i < 250; i++) d.onFrame(frame(1, i));
        List<Event> evs = d.poll();
        assertEquals(2, evs.size());              // 250 khung = 2 lần đủ 100
        assertEquals("MOTION", evs.get(0).type());
        assertEquals(0, d.poll().size());         // poll xong thì hàng đợi rỗng
    }

    @Test
    void sensorsAreIndependent() {
        MotionDetector d = new MotionDetector();
        for (long i = 0; i < 100; i++) { d.onFrame(frame(1, i)); d.onFrame(frame(2, i)); }
        assertEquals(2, d.poll().size());         // mỗi cảm biến 1 event
    }
}