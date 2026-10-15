package vn.wisense.detect.fall;

import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.model.Detector;
import vn.wisense.common.model.Event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Phát hiện té ngã bằng ngưỡng (TV4).
 *
 * <p>Ý tưởng: té ngã = một biến động lớn và ngắn, rồi người nằm yên 3–5 giây.
 * Mỗi khung CSI được rút gọn thành một số x = biên độ trung bình của các sóng mang con.
 * Máy trạng thái chạy riêng cho từng cảm biến:
 *
 * <pre>
 *  IDLE   : theo dõi đường nền (EMA) và độ nhiễu. Cần {@code onsetFrames}=3 khung liên tiếp
 *           lệch quá ngưỡng cao mới bắt đầu tính, và trước đó phòng phải yên ít nhất
 *           {@code minPreCalmUs} (loại đi bộ liên tục).
 *  BURST  : biến động đang diễn ra. Quá dài (> maxBurstUs) và vẫn nhiễu -> đi lại, bỏ.
 *           Đỉnh quá nhỏ -> ngồi xuống / cúi nhặt đồ, bỏ.
 *  QUIET  : sau biến động phải yên đủ {@code quietUs} (3 giây; chỉnh được đến 5 giây).
 *           Có chuyển động trở lại -> bỏ. Đủ yên -> báo FALL.
 *  Sau mỗi lần báo, nghỉ {@code cooldownUs} = 5 giây.
 * </pre>
 *
 * Mọi mốc thời gian dùng timestampUs của gói, nên chạy đúng cả với dữ liệu phát lại.
 * Các ngưỡng đều tương đối (theo nhiễu và đường nền) nhưng vẫn cần chỉnh bằng dữ liệu thật ở ngày 4.
 */
public final class FallDetector implements Detector {

    /** Toàn bộ tham số, chỉnh ở một chỗ. */
    public record Config(
            int warmupFrames,        // số khung đầu để học đường nền
            double alpha,            // hệ số EMA của đường nền (khi yên)
            double kHigh,            // ngưỡng cao = kHigh * độ lệch chuẩn nhiễu
            double minRelative,      // ... nhưng không thấp hơn minRelative * đường nền
            double sigmaFloorRel,    // sàn của độ lệch chuẩn nhiễu theo đường nền
            int onsetFrames,         // số khung liên tiếp để bắt đầu biến động (=3)
            long minPreCalmUs,       // phòng phải yên trước biến động
            long maxBurstUs,         // biến động dài hơn mức này thì không phải ngã
            double lowFactor,        // ngưỡng thấp (kết thúc biến động) = lowFactor * ngưỡng cao
            double peakMult,         // đỉnh phải >= peakMult * ngưỡng cao
            int shortWin,            // cửa sổ ngắn đo độ rung (số khung)
            double agitK,            // rung mạnh nếu std cửa sổ ngắn > agitK * nhiễu
            int endFrames,           // số khung yên liên tiếp để kết thúc biến động
            long quietUs,            // thời gian yên cần có sau biến động (3–5 giây)
            double quietK,           // yên nếu std <= quietK * nhiễu
            double quietMinRel,      // ... hoặc <= quietMinRel * đường nền (lấy số lớn hơn)
            long cooldownUs,         // nghỉ sau mỗi lần báo (5 giây)
            long gapResetUs) {       // khoảng trống lớn hơn mức này thì học lại từ đầu

        public static Config defaults() {
            return new Config(200, 0.01, 6.0, 0.15, 0.005,
                    3, 1_000_000L, 2_000_000L, 0.5, 2.0,
                    20, 4.0, 3,
                    3_000_000L, 3.0, 0.02,
                    5_000_000L, 2_000_000L);
        }

        public Config withQuietUs(long v) {
            return new Config(warmupFrames, alpha, kHigh, minRelative, sigmaFloorRel, onsetFrames,
                    minPreCalmUs, maxBurstUs, lowFactor, peakMult, shortWin, agitK, endFrames,
                    v, quietK, quietMinRel, cooldownUs, gapResetUs);
        }
    }

    private enum Phase { IDLE, BURST, QUIET }

    /** Trạng thái riêng của từng cảm biến. */
    private static final class S {
        long lastSeq = -1, lastTs = -1;
        int frames;
        double baseline, var;
        Phase phase = Phase.IDLE;

        // IDLE
        int highRun;
        long runStartUs, calmSinceUs = -1;
        double runPeak;
        long cooldownUntilUs = Long.MIN_VALUE;

        // cửa sổ ngắn
        double[] win;
        int wi, wn;
        double wsum, wsumsq;

        // BURST
        long burstStartUs;
        double peak, frozenHighThr, frozenSigma, frozenBase;
        int inactiveRun;

        // QUIET
        long quietStartUs;
        int qn;
        double qsum, qsumsq;

        void resetAll(int shortWin) {
            lastSeq = -1; lastTs = -1; frames = 0; baseline = 0; var = 0;
            phase = Phase.IDLE; highRun = 0; calmSinceUs = -1; runPeak = 0;
            win = new double[shortWin]; wi = 0; wn = 0; wsum = 0; wsumsq = 0;
        }

        void push(double x) {
            if (wn == win.length) {
                double old = win[wi];
                wsum -= old; wsumsq -= old * old;
            } else {
                wn++;
            }
            win[wi] = x; wi = (wi + 1) % win.length;
            wsum += x; wsumsq += x * x;
        }

        double shortStd() {
            if (wn < win.length) return 0;
            double m = wsum / wn;
            return Math.sqrt(Math.max(0, wsumsq / wn - m * m));
        }
    }

    private final Config cfg;
    private final LongSupplier epochSeconds;
    private final Map<Integer, S> sensors = new HashMap<>();
    private final List<Event> events = new ArrayList<>();

    public FallDetector() {
        this(Config.defaults(), () -> System.currentTimeMillis() / 1000);
    }

    public FallDetector(Config cfg, LongSupplier epochSeconds) {
        this.cfg = cfg;
        this.epochSeconds = epochSeconds;
    }

    @Override
    public synchronized void onFrame(CsiFrame f) {
        if (f == null || f.amplitude() == null || f.amplitude().length == 0) return;
        double x = mean(f.amplitude());
        if (Double.isNaN(x) || Double.isInfinite(x)) return;

        S s = sensors.computeIfAbsent(f.sensorId(), id -> {
            S n = new S();
            n.resetAll(cfg.shortWin());
            return n;
        });

        long ts = f.timestampUs();
        // bỏ gói trùng / sai thứ tự (TV2 đã lọc, ở đây phòng thêm)
        if (s.lastSeq >= 0 && (f.seq() <= s.lastSeq || ts <= s.lastTs)) return;
        // mất tín hiệu lâu: học lại từ đầu, tránh kết luận trên dữ liệu đứt đoạn
        if (s.lastTs >= 0 && ts - s.lastTs > cfg.gapResetUs()) {
            long keepCooldown = s.cooldownUntilUs;
            s.resetAll(cfg.shortWin());
            s.cooldownUntilUs = keepCooldown;
        }
        s.lastSeq = f.seq();
        s.lastTs = ts;
        s.frames++;
        s.push(x);

        // giai đoạn học đường nền
        if (s.frames == 1) {
            s.baseline = x;
        }
        if (s.frames <= cfg.warmupFrames()) {
            double dev = x - s.baseline;
            s.baseline += 0.02 * dev;
            s.var += 0.02 * (dev * dev - s.var);
            s.calmSinceUs = ts;
            return;
        }

        switch (s.phase) {
            case IDLE -> idle(f.sensorId(), s, x, ts);
            case BURST -> burst(s, x, ts);
            case QUIET -> quiet(f.sensorId(), s, x, ts);
        }
    }

    @Override
    public synchronized List<Event> poll() {
        List<Event> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    // ---------------------------------------------------------------- IDLE

    private double sigma(S s) {
        return Math.max(Math.sqrt(s.var), cfg.sigmaFloorRel() * Math.abs(s.baseline));
    }

    private double highThr(S s) {
        return Math.max(cfg.kHigh() * sigma(s), cfg.minRelative() * Math.abs(s.baseline));
    }

    private void idle(int sensorId, S s, double x, long ts) {
        double d = Math.abs(x - s.baseline);
        double thr = highThr(s);

        if (d <= thr) {
            s.highRun = 0;
            s.runPeak = 0;
            if (s.calmSinceUs < 0) s.calmSinceUs = ts;
            double dev = x - s.baseline;           // chỉ học khi yên
            s.baseline += cfg.alpha() * dev;
            s.var += cfg.alpha() * (dev * dev - s.var);
            return;
        }

        // khung lệch quá ngưỡng cao
        if (s.highRun == 0) {
            s.runStartUs = ts;
            s.runPeak = 0;
        }
        s.highRun++;
        s.runPeak = Math.max(s.runPeak, d);

        if (s.highRun == cfg.onsetFrames()) {
            long preCalm = s.calmSinceUs >= 0 ? s.runStartUs - s.calmSinceUs : 0;
            boolean eligible = preCalm >= cfg.minPreCalmUs() && ts >= s.cooldownUntilUs;
            s.calmSinceUs = -1;                    // có biến động thật, hết "yên"
            if (eligible) {
                s.phase = Phase.BURST;
                s.burstStartUs = s.runStartUs;
                s.peak = s.runPeak;
                s.frozenHighThr = thr;
                s.frozenSigma = sigma(s);
                s.frozenBase = s.baseline;
                s.inactiveRun = 0;
                return;
            }
        }

        // lệch kéo dài mà không đủ điều kiện: đang đi lại hoặc đổi môi trường -> dựng lại đường nền
        if (ts - s.runStartUs > cfg.maxBurstUs()) {
            s.baseline = x;
            s.highRun = 0;
            s.runPeak = 0;
            s.calmSinceUs = -1;
        }
    }

    // --------------------------------------------------------------- BURST

    private void burst(S s, double x, long ts) {
        double d = Math.abs(x - s.frozenBase);
        s.peak = Math.max(s.peak, d);

        double lowThr = cfg.lowFactor() * s.frozenHighThr;
        boolean agitated = s.shortStd() > cfg.agitK() * s.frozenSigma;
        boolean active = d > lowThr || agitated;
        s.inactiveRun = active ? 0 : s.inactiveRun + 1;

        if (s.inactiveRun >= cfg.endFrames()) {
            endBurst(s, x, ts);
        } else if (ts - s.burstStartUs > cfg.maxBurstUs()) {
            if (!agitated) {
                endBurst(s, x, ts);               // đã ổn định ở mức mới (ví dụ nằm xuống)
            } else {
                abandon(s, x, ts);                // vẫn nhiễu: đi lại liên tục
            }
        }
    }

    private void endBurst(S s, double x, long ts) {
        if (s.peak < cfg.peakMult() * s.frozenHighThr) {
            abandon(s, x, ts);                    // biến động nhỏ: ngồi xuống, cúi nhặt đồ
            return;
        }
        s.phase = Phase.QUIET;
        s.quietStartUs = ts;
        s.qn = 0;
        s.qsum = 0;
        s.qsumsq = 0;
    }

    private void abandon(S s, double x, long ts) {
        s.phase = Phase.IDLE;
        s.baseline = x;
        s.highRun = 0;
        s.runPeak = 0;
        s.calmSinceUs = -1;
    }

    // --------------------------------------------------------------- QUIET

    private void quiet(int sensorId, S s, double x, long ts) {
        s.qn++;
        s.qsum += x;
        s.qsumsq += x * x;

        // chuyển động trở lại trước khi đủ thời gian yên -> không phải ngã
        if (s.shortStd() > cfg.agitK() * s.frozenSigma) {
            abandon(s, x, ts);
            return;
        }
        if (ts - s.quietStartUs < cfg.quietUs()) return;

        double mean = s.qsum / s.qn;
        double qstd = Math.sqrt(Math.max(0, s.qsumsq / s.qn - mean * mean));
        double quietThr = Math.max(cfg.quietK() * s.frozenSigma, cfg.quietMinRel() * Math.abs(mean));

        if (qstd <= quietThr) {
            double ps = Math.min(1.0, s.peak / (3.0 * s.frozenHighThr));
            double qs = Math.max(0.0, 1.0 - qstd / quietThr);
            double conf = 0.5 + 0.5 * (0.6 * ps + 0.4 * qs);   // heuristic, không phải xác suất
            events.add(Event.fall(sensorId, epochSeconds.getAsLong(), Math.min(1.0, conf)));
            s.cooldownUntilUs = ts + cfg.cooldownUs();
            s.phase = Phase.IDLE;
            s.baseline = mean;                    // người nằm yên ở mức mới
            s.highRun = 0;
            s.runPeak = 0;
            s.calmSinceUs = ts;
        } else {
            abandon(s, x, ts);
        }
    }

    private static double mean(float[] a) {
        double sum = 0;
        for (float v : a) sum += v;
        return sum / a.length;
    }
}
