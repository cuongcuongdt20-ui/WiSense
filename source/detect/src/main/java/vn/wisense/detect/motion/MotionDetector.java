package vn.wisense.detect.motion;

import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.model.Detector;
import vn.wisense.common.model.Event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phát hiện có người / đang di chuyển (TV3).
 * Đo độ lắc của biên độ trong cửa sổ 1 giây (theo timestampUs, không theo số khung) bằng CV²
 * (phương sai chia bình phương trung bình), nên không phụ thuộc thang biên độ (AGC, vị trí đặt).
 * Ngưỡng = k × mức nền học ở vài giây đầu.
 */
public final class MotionDetector implements Detector {

    public record Config(
            long windowUs,          // độ dài cửa sổ và khoảng cách giữa hai lần đánh giá (1 giây)
            int minFrames,          // cửa sổ ít khung hơn mức này thì không đánh giá
            int warmupWindows,      // số lần đánh giá đầu để học mức nền (5 giây), giả định phòng gần yên
            double k,               // ngưỡng = k * mức nền
            double minThreshold,    // sàn của ngưỡng (đơn vị CV²), phòng khi mức nền ≈ 0
            double alpha,           // tốc độ nền thích nghi khi không có chuyển động
            double releaseFactor) { // tắt chuyển động khi raw < releaseFactor * ngưỡng

        public static Config defaults() {
            return new Config(1_000_000L, 10, 5, 4.0, 1e-4, 0.05, 0.7);
        }
    }

    private record Sample(long ts, float[] amp) {}

    /** Trạng thái riêng của từng cảm biến. */
    private static final class S {
        long lastSeq = -1, lastTs = -1;
        long nextEvalUs;            // đủ mốc thời gian này mới đánh giá
        int len = -1;               // số sóng mang con của khung trước
        int warm;                   // số lần đã học nền
        double baseline;
        boolean moving;
        final ArrayDeque<Sample> window = new ArrayDeque<>();
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
        long ts = f.timestampUs();

        // Phát hiện reset ESP32 trước bộ lọc gói trùng.
        if (s.lastSeq >= 0 && s.lastTs >= 0) {
            boolean seqReset = s.lastSeq - f.seq() > 100;
            boolean timestampReset = s.lastTs - ts > 1_000_000L;

            if (seqReset || timestampReset) {
                // Xóa toàn bộ trạng thái phụ thuộc dữ liệu cũ.
                s.window.clear();
                s.lastSeq = -1;
                s.lastTs = -1;
                s.nextEvalUs = 0;
                s.len = -1;
                s.warm = 0;
                s.baseline = 0;
                s.moving = false;
            }
        }

        // Loại gói trùng / sai thứ tự nếu không phải reset.
        if (s.lastSeq >= 0 && (f.seq() <= s.lastSeq || ts <= s.lastTs)) {
            return;
        }

        s.lastSeq = f.seq();
        s.lastTs = ts;

        // số sóng mang con đổi: các khung cũ không còn so sánh được, học lại từ đầu
        int len = f.amplitude().length;
        if (len != s.len) {
            s.window.clear();
            s.moving = false;
            s.warm = 0;
            s.baseline = 0;
            s.len = len;
        }

        // cửa sổ theo thời gian: thêm khung mới, bỏ các khung cũ hơn windowUs
        s.window.addLast(new Sample(ts, f.amplitude().clone()));
        while (ts - s.window.peekFirst().ts() > cfg.windowUs()) s.window.removeFirst();

        // cửa sổ vừa bắt đầu lại (khung đầu, đổi số sóng mang con, hoặc sau khoảng trống dài):
        // phải đủ một cửa sổ mới đầy mới đánh giá
        if (s.window.size() == 1) s.nextEvalUs = ts + cfg.windowUs();

        if (ts < s.nextEvalUs || s.window.size() < cfg.minFrames()) return;
        s.nextEvalUs = ts + cfg.windowUs();

        List<float[]> frames = new ArrayList<>(s.window.size());
        for (Sample x : s.window) frames.add(x.amp());
        double raw = meanNormVariance(frames);

        // học mức nền: lấy giây yên nhất, chịu được lúc đầu có người đi qua một phần
        if (s.warm < cfg.warmupWindows()) {
            s.baseline = s.warm == 0 ? raw : Math.min(s.baseline, raw);
            s.warm++;
            events.add(Event.motion(f.sensorId(), 0.0, false));
            return;
        }

        double thr = Math.max(cfg.minThreshold(), cfg.k() * s.baseline);

        if (!s.moving && raw > thr) {
            s.moving = true;
        } else if (s.moving && raw < cfg.releaseFactor() * thr) {
            s.moving = false;
        }

        // nền chỉ thích nghi khi yên, nên chuyển động không kéo nền lên
        if (!s.moving) s.baseline += cfg.alpha() * (raw - s.baseline);

        // thang log: raw = 10 × ngưỡng thì level = 1, biểu đồ không chỉ có 0 và 1
        double level = Math.min(1.0, Math.log1p(raw / thr) / Math.log1p(10));
        events.add(Event.motion(f.sensorId(), level, s.moving));
    }

    @Override
    public synchronized List<Event> poll() {
        List<Event> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    /**
     * CV² trung bình qua các sóng mang con: mỗi sóng mang con tính phương sai / (trung bình)².
     * Bỏ sóng mang con yếu (null hoặc dưới 10% mức trung bình chung).
     */
    static double meanNormVariance(List<float[]> window) {
        int n = window.size();
        int k = window.get(0).length;
        double[] mean = new double[k];
        double overall = 0;
        for (int c = 0; c < k; c++) {
            double m = 0;
            for (float[] a : window) m += a[c];
            mean[c] = m / n;
            overall += mean[c];
        }
        overall /= k;

        double sum = 0;
        int used = 0;
        for (int c = 0; c < k; c++) {
            if (mean[c] <= 1e-9 || mean[c] < 0.1 * overall) continue;
            double var = 0;
            for (float[] a : window) {
                double d = a[c] - mean[c];
                var += d * d;
            }
            sum += (var / n) / (mean[c] * mean[c]);
            used++;
        }
        return used == 0 ? 0 : sum / used;
    }
}