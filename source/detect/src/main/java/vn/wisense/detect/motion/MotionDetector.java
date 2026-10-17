package vn.wisense.detect.motion;

import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.model.Detector;
import vn.wisense.common.model.Event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Phát hiện có người / đang di chuyển (TV3). Bản khung: chưa có thuật toán. */
public final class MotionDetector implements Detector {

    /** Mọi tham số gom một chỗ để chỉnh. */
    public record Config(int windowFrames) {      // ~100 khung = 1 giây
        public static Config defaults() { return new Config(100); }
    }

    /** Trạng thái riêng của từng cảm biến. */
    private static final class S {
        long lastSeq = -1, lastTs = -1;
        int count;
    }

    private final Config cfg;
    private final Map<Integer, S> sensors = new HashMap<>();
    private final List<Event> events = new ArrayList<>();

    public MotionDetector() { this(Config.defaults()); }
    public MotionDetector(Config cfg) { this.cfg = cfg; }

    @Override
    public synchronized void onFrame(CsiFrame f) {
        if (f == null || f.amplitude() == null || f.amplitude().length == 0) return;

        S s = sensors.computeIfAbsent(f.sensorId(), id -> new S());

        // bỏ gói trùng / sai thứ tự
        if (s.lastSeq >= 0 && (f.seq() <= s.lastSeq || f.timestampUs() <= s.lastTs)) return;
        s.lastSeq = f.seq();
        s.lastTs = f.timestampUs();

        s.count++;
        if (s.count >= cfg.windowFrames()) {
            s.count = 0;
            events.add(Event.motion(f.sensorId(), 0.0, false));   // tạm: level=0, chưa chuyển động
        }
    }

    @Override
    public synchronized List<Event> poll() {
        List<Event> out = new ArrayList<>(events);
        events.clear();
        return out;
    }
}