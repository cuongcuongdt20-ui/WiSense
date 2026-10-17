package vn.wisense.detect.breathing;

import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.model.Detector;
import vn.wisense.common.model.Event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Ước lượng nhịp thở bằng FFT trên biên độ trung bình (TV3). Bản khung: mới có gom dữ liệu và resample. */
public final class BreathingEstimator implements Detector {

    public record Config(
            double windowSec,    // độ dài cửa sổ, 60 giây
            double fs) {         // tần số lấy mẫu lại, 10 Hz

        public static Config defaults() { return new Config(60.0, 10.0); }
    }

    /** Trạng thái riêng của từng cảm biến. */
    private static final class S {
        long lastSeq = -1, lastTs = -1;
        final ArrayDeque<double[]> buf = new ArrayDeque<>();   // mỗi phần tử: {timestampUs, giá trị}
    }

    private final Config cfg;
    private final Map<Integer, S> sensors = new HashMap<>();
    private final List<Event> events = new ArrayList<>();

    public BreathingEstimator() { this(Config.defaults()); }
    public BreathingEstimator(Config cfg) { this.cfg = cfg; }

    @Override
    public synchronized void onFrame(CsiFrame f) {
        if (f == null || f.amplitude() == null || f.amplitude().length == 0) return;

        S s = sensors.computeIfAbsent(f.sensorId(), id -> new S());

        // bỏ gói trùng / sai thứ tự
        if (s.lastSeq >= 0 && (f.seq() <= s.lastSeq || f.timestampUs() <= s.lastTs)) return;
        s.lastSeq = f.seq();
        s.lastTs = f.timestampUs();

        s.buf.addLast(new double[]{f.timestampUs(), meanAmp(f.amplitude())});

        // bỏ các mẫu cũ hơn cửa sổ
        long cutoff = f.timestampUs() - (long) (cfg.windowSec() * 1e6);
        while (s.buf.peekFirst()[0] < cutoff) s.buf.removeFirst();
    }

    @Override
    public synchronized List<Event> poll() {
        List<Event> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    /** Trung bình biên độ các sóng mang con, bỏ sóng mang con null (≈ 0). */
    static double meanAmp(float[] a) {
        double sum = 0;
        int used = 0;
        for (float v : a) {
            if (v > 1e-3f) { sum += v; used++; }
        }
        return used == 0 ? 0 : sum / used;
    }

    /**
     * Dựng lại tín hiệu trên lưới đều bằng nội suy tuyến tính.
     * pts: các cặp {timestampUs, giá trị}, đã sắp theo thời gian, ít nhất 2 phần tử.
     * Trả về các mẫu cách nhau 1/fs giây, bắt đầu tại thời điểm của pts[0].
     */
    static double[] resample(double[][] pts, double fs) {
        double t0 = pts[0][0];
        double t1 = pts[pts.length - 1][0];
        int n = (int) ((t1 - t0) / 1e6 * fs);
        double[] x = new double[n];
        int j = 0;
        for (int i = 0; i < n; i++) {
            double t = t0 + i * 1e6 / fs;
            while (j < pts.length - 2 && pts[j + 1][0] < t) j++;
            double ta = pts[j][0];
            double tb = pts[j + 1][0];
            double w = tb > ta ? (t - ta) / (tb - ta) : 0;
            w = Math.max(0, Math.min(1, w));
            x[i] = pts[j][1] * (1 - w) + pts[j + 1][1] * w;
        }
        return x;
    }
}