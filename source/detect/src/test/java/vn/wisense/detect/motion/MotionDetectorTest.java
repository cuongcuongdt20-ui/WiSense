package vn.wisense.detect.motion;

import org.junit.jupiter.api.Test;
import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.model.Event;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MotionDetectorTest {

    private static CsiFrame frame(int sensor, long i) {
        return new CsiFrame(sensor, i, i * 10_000L, -50, new float[64], new float[64]);
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