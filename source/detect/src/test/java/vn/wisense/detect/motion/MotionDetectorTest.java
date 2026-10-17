package vn.wisense.detect.motion;

import org.junit.jupiter.api.Test;
import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.model.Event;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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