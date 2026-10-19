
package vn.wisense.detect.motion;

import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.model.Detector;
import vn.wisense.common.model.Event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MotionDetector implements Detector {

    private static final System.Logger LOG =
            System.getLogger(MotionDetector.class.getName());

    public record Config(
            long windowUs,
            int minFrames,
            int warmupWindows,
            double k,
            double minThreshold,
            double alpha,
            double releaseFactor) {

        public static Config defaults() {
            return new Config(1_000_000L, 10, 5, 4.0,
                    1e-4, 0.05, 0.7);
        }
    }

    private record Sample(long ts, float[] amp) {}

    private static final class S {
        long lastSeq = -1;
        long lastTs = -1;
        long nextEvalUs;
        int len = -1;
        int warm;
        double baseline;
        boolean moving;

        final ArrayDeque<Sample> window = new ArrayDeque<>();
    }

    private final Config cfg;
    private final Map<Integer, S> sensors = new HashMap<>();
    private final List<Event> events = new ArrayList<>();

    public MotionDetector() {
        this(Config.defaults());
    }

    public MotionDetector(Config cfg) {
        this.cfg = cfg;
    }

    /** Xóa trạng thái phụ thuộc vào phiên chạy trước của cảm biến. */
    private static void resetSensor(S s) {
        s.window.clear();
        s.lastSeq = -1;
        s.lastTs = -1;
        s.nextEvalUs = 0;
        s.len = -1;
        s.warm = 0;
        s.baseline = 0;
        s.moving = false;
    }

    @Override
    public synchronized void onFrame(CsiFrame f) {
        if (f == null || f.amplitude() == null
                || f.amplitude().length == 0) {
            return;
        }

        S s = sensors.computeIfAbsent(f.sensorId(), id -> new S());
        long ts = f.timestampUs();

        // Nhận diện ESP32 khởi động lại.
        if (s.lastSeq >= 0) {
            boolean seqReset = f.seq() < s.lastSeq
                    && s.lastSeq - f.seq() > 100;

            boolean timestampReset = ts < s.lastTs
                    && s.lastTs - ts > 1_000_000L;

            if (seqReset || timestampReset) {
                resetSensor(s);
                LOG.log(System.Logger.Level.INFO,
                        "ESP32 reset detected; relearning motion baseline for sensor "
                                + f.sensorId());
            } else if (f.seq() <= s.lastSeq || ts <= s.lastTs) {
                // Gói trùng hoặc sai thứ tự, không phải reset lớn.
                return;
            }
        }

        s.lastSeq = f.seq();
        s.lastTs = ts;

        int len = f.amplitude().length;

        // Số sóng mang con thay đổi: bắt đầu học nền lại.
        if (len != s.len) {
            s.window.clear();
            s.moving = false;
            s.warm = 0;
            s.baseline = 0;
            s.len = len;
        }

        s.window.addLast(new Sample(ts, f.amplitude().clone()));

        while (!s.window.isEmpty()
                && ts - s.window.peekFirst().ts() > cfg.windowUs()) {
            s.window.removeFirst();
        }

        // Chờ một cửa sổ mới đầy trước khi đánh giá.
        if (s.window.size() == 1) {
            s.nextEvalUs = ts + cfg.windowUs();
        }

        if (ts < s.nextEvalUs || s.window.size() < cfg.minFrames()) {
            return;
        }

        s.nextEvalUs = ts + cfg.windowUs();

        List<float[]> frames = new ArrayList<>(s.window.size());
        for (Sample sample : s.window) {
            frames.add(sample.amp());
        }

        double raw = meanNormVariance(frames);

        // Học mức nền thấp nhất trong giai đoạn khởi động.
        if (s.warm < cfg.warmupWindows()) {
            s.baseline = s.warm == 0
                    ? raw
                    : Math.min(s.baseline, raw);

            s.warm++;

            if (s.warm == cfg.warmupWindows()) {
                LOG.log(System.Logger.Level.INFO,
                        "Motion baseline learned: sensor="
                                + f.sensorId()
                                + ", baselineCv2=" + s.baseline
                                + ", warmupWindows=" + s.warm);
            }

            events.add(Event.motion(f.sensorId(), 0.0, false));
            return;
        }

        double thr = Math.max(
                cfg.minThreshold(), cfg.k() * s.baseline);

        if (!s.moving && raw > thr) {
            s.moving = true;
        } else if (s.moving
                && raw < cfg.releaseFactor() * thr) {
            s.moving = false;
        }

        // Chỉ cập nhật nền khi không phát hiện chuyển động.
        if (!s.moving) {
            s.baseline += cfg.alpha() * (raw - s.baseline);
        }

        double level = Math.min(
                1.0, Math.log1p(raw / thr) / Math.log1p(10));

        events.add(Event.motion(f.sensorId(), level, s.moving));
    }

    @Override
    public synchronized List<Event> poll() {
        List<Event> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    /**
     * CV² trung bình trên các sóng mang con.
     * Bỏ sóng mang con yếu hơn 10% mức trung bình chung.
     */
    static double meanNormVariance(List<float[]> window) {
        if (window == null || window.isEmpty()
                || window.get(0) == null
                || window.get(0).length == 0) {
            return 0;
        }

        int n = window.size();
        int k = window.get(0).length;
        double[] mean = new double[k];
        double overall = 0;

        for (float[] a : window) {
            if (a == null || a.length != k) {
                return 0;
            }
        }

        for (int c = 0; c < k; c++) {
            double m = 0;

            for (float[] a : window) {
                m += a[c];
            }

            mean[c] = m / n;
            overall += mean[c];
        }

        overall /= k;

        double sum = 0;
        int used = 0;

        for (int c = 0; c < k; c++) {
            if (mean[c] <= 1e-9 || mean[c] < 0.1 * overall) {
                continue;
            }

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
