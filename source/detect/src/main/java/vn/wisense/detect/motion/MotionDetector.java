package vn.wisense.detect.motion;

import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.model.Detector;
import vn.wisense.common.model.Event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Phát hiện có người / đang di chuyển bằng phương sai biên độ trong cửa sổ 1 giây (TV3). */
public final class MotionDetector implements Detector {

    /** Mọi tham số gom một chỗ để chỉnh. */
    public record Config(
            int windowFrames,       // cửa sổ trượt, ~100 khung = 1 giây
            double threshold,       // phương sai vượt mức này thì coi là chuyển động
            double releaseFactor) { // tắt chuyển động khi phương sai < releaseFactor * threshold

        public static Config defaults() { return new Config(100, 0.5, 0.7); }
    }

    /** Trạng thái riêng của từng cảm biến. */
    private static final class S {
        long lastSeq = -1, lastTs = -1;
        int count;
        boolean moving;
        final ArrayDeque<float[]> window = new ArrayDeque<>();
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

        // cửa sổ trượt: thêm khung mới, bỏ khung cũ nhất
        s.window.addLast(f.amplitude().clone());
        if (s.window.size() > cfg.windowFrames()) s.window.removeFirst();

        // mỗi windowFrames khung (~1 giây) và khi cửa sổ đã đầy thì mới đánh giá
        s.count++;
        if (s.count < cfg.windowFrames() || s.window.size() < cfg.windowFrames()) return;
        s.count = 0;

        double raw = meanVariance(new ArrayList<>(s.window));

        if (!s.moving && raw > cfg.threshold()) {
            s.moving = true;
        } else if (s.moving && raw < cfg.releaseFactor() * cfg.threshold()) {
            s.moving = false;
        }

        double level = Math.min(1.0, raw / (2 * cfg.threshold()));
        events.add(Event.motion(f.sensorId(), level, s.moving));
    }

    @Override
    public synchronized List<Event> poll() {
        List<Event> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    /** Phương sai trung bình qua các sóng mang con. Mỗi phần tử của window là amplitude[] của một khung. */
    static double meanVariance(List<float[]> window) {
        int n = window.size();
        int k = window.get(0).length;
        double sumVar = 0;
        int used = 0;
        for (int c = 0; c < k; c++) {
            double mean = 0;
            for (float[] a : window) mean += a[c];
            mean /= n;
            if (mean < 1e-3) continue;              // sóng mang con null: bỏ
            double var = 0;
            for (float[] a : window) {
                double d = a[c] - mean;
                var += d * d;
            }
            sumVar += var / n;
            used++;
        }
        return used == 0 ? 0 : sumVar / used;
    }
}