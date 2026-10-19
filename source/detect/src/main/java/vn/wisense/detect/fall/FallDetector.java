package vn.wisense.detect.fall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.model.Detector;
import vn.wisense.common.model.Event;

/**
 * Phát hiện té ngã bằng ngưỡng (TV4).
 *
 * <h3>Đặc trưng</h3>
 * Mỗi khung được chuẩn hóa theo biên độ trung bình của chính nó: ã[k] = a[k] / mean(a).
 * Phép chia này triệt tiêu việc AGC đổi độ lợi của cả khung (mọi sóng mang con nhân cùng một hệ số).
 * Sau đó so từng sóng mang con với đường nền b[k] (trung bình trượt khi phòng yên):
 * <pre>  d = (1/K) * Σ_k |ã[k] − b[k]|</pre>
 * Cách cộng độ lệch từng sóng mang con (thay vì lấy trung bình biên độ) giữ lại chuyển động làm các
 * sóng mang con tăng giảm ngược nhau, vì những thay đổi này triệt tiêu nhau khi lấy trung bình.
 * Độ rung ngắn hạn e = (1/K) * Σ_k |ã_t[k] − ã_{t-1}[k]|, lấy trung bình trên cửa sổ ngắn.
 *
 * <h3>Máy trạng thái (riêng từng cảm biến)</h3>
 * <pre>
 *  IDLE  : theo dõi đường nền và mức nhiễu. 3 khung liên tiếp có d vượt ngưỡng cao -> BURST
 *          (không còn điều kiện phòng phải yên trước đó, nên ngã khi đang đi vẫn bắt được).
 *  BURST : biến động đang diễn ra. Quá dài mà vẫn rung (đi lại) -> bỏ.
 *          Khi kết thúc, đỉnh phải lớn so với ngưỡng và lớn hơn hẳn đỉnh d của vài giây ngay trước đó
 *          (loại ngồi xuống, cúi nhặt đồ, và đi bộ rồi dừng, kể cả đi ngắt quãng).
 *  QUIET : phải yên {@code quietUs} (3 giây; chỉnh được đến 5). Có chuyển động lại -> bỏ.
 *          Đủ yên -> báo FALL, rồi nghỉ {@code cooldownUs} = 5 giây.
 * </pre>
 * Mọi mốc thời gian dùng timestampUs của gói, nên chạy đúng cả với dữ liệu phát lại.
 * Ngưỡng đều tương đối (theo nhiễu và đường nền đã chuẩn hóa) nhưng vẫn cần chỉnh bằng dữ liệu thật.
 *
 * <h3>Gói lùi</h3>
 * Gói trùng hoặc lùi ít bị bỏ. Nếu seq lùi hơn {@code backSeqTol} hoặc timestamp lùi hơn
 * {@code backTsUs} thì coi là ESP32 vừa khởi động lại: học lại từ đầu.
 */
public final class FallDetector implements Detector {

    /** Toàn bộ tham số, chỉnh ở một chỗ. */
    public record Config(
            int warmupFrames,        // số khung đầu để học đường nền
            double alpha,            // hệ số EMA của đường nền và mức nhiễu (khi yên)
            double kHigh,            // ngưỡng cao = kHigh * mức nhiễu d
            double minHigh,          // ... nhưng không thấp hơn minHigh (độ lệch tương đối so với đường nền)
            int onsetFrames,         // số khung liên tiếp để bắt đầu biến động (=3)
            long maxBurstUs,         // biến động dài hơn mức này mà vẫn rung thì không phải ngã
            double lowFactor,        // ngưỡng thấp (kết thúc biến động) = lowFactor * ngưỡng cao
            double peakMult,         // đỉnh phải >= peakMult * ngưỡng cao
            double peakVsRecent,     // đỉnh phải >= peakVsRecent * đỉnh d gần đây (trước biến động)
            double recentDecay,      // hệ số phân rã mỗi khung của "đỉnh d gần đây" (0,9965 ~ nửa đời 2 giây)
            int shortWin,            // cửa sổ ngắn đo độ rung (số khung)
            double agitK,            // rung mạnh nếu e trung bình > agitK * mức nhiễu e
            int endFrames,           // số khung yên liên tiếp để kết thúc biến động
            long quietUs,            // thời gian yên cần có sau biến động (3–5 giây)
            double quietK,           // yên nếu độ lệch chuẩn theo thời gian <= quietK * mức nhiễu d
            double quietMin,         // ... hoặc <= quietMin (lấy số lớn hơn)
            long cooldownUs,         // nghỉ sau mỗi lần báo (5 giây)
            long gapResetUs,         // mất gói lâu hơn mức này thì học lại từ đầu
            long backSeqTol,         // seq lùi hơn mức này = ESP32 khởi động lại
            long backTsUs) {         // timestamp lùi hơn mức này = ESP32 khởi động lại

        public static Config defaults() {
            return new Config(200, 0.01, 6.0, 0.05,
                    3, 2_000_000L, 0.5, 2.0, 1.5, 0.9965,
                    20, 3.0, 3,
                    3_000_000L, 4.0, 0.01,
                    5_000_000L, 2_000_000L, 100, 1_000_000L);
        }

        public Config withQuietUs(long v) {
            return new Config(warmupFrames, alpha, kHigh, minHigh, onsetFrames, maxBurstUs, lowFactor,
                    peakMult, peakVsRecent, recentDecay, shortWin, agitK, endFrames,
                    v, quietK, quietMin, cooldownUs, gapResetUs, backSeqTol, backTsUs);
        }
    }

    private enum Phase { IDLE, BURST, QUIET }

    /** Trạng thái riêng của từng cảm biến. */
    private static final class S {
        final int k;
        long lastSeq = -1, lastTs = -1;
        long cooldownUntilUs = Long.MIN_VALUE;
        int frames;
        Phase phase = Phase.IDLE;

        final double[] base, cur, prev;
        boolean[] valid;               // sóng mang con dùng được (bỏ sóng mang con rỗng của LLTF)
        int nValid;
        boolean hasPrev;
        double dNoise, eNoise;
        double recent, recentBefore;   // đỉnh d gần đây (phân rã dần)

        // cửa sổ ngắn của e
        final double[] ewin;
        int wi, wn;
        double wsum;

        // IDLE
        int highRun;
        long runStartUs;
        double runPeak, actAtRun;

        // BURST
        long burstStartUs;
        double peak, frozenThr, frozenAct;
        int inactiveRun;

        // QUIET
        long quietStartUs;
        int qn;
        final double[] qsum, qsumsq;

        S(int k, int shortWin) {
            this.k = k;
            base = new double[k];
            cur = new double[k];
            prev = new double[k];
            qsum = new double[k];
            qsumsq = new double[k];
            ewin = new double[shortWin];
        }

        /** Sóng mang con có biên độ ~0 ngay từ đầu (DC, dải bảo vệ) bị loại, vì chúng chỉ làm loãng đặc trưng. */
        void initMask(float[] a, double overallMean) {
            valid = new boolean[k];
            nValid = 0;
            for (int i = 0; i < k; i++) {
                valid[i] = a[i] > 0.1 * overallMean;
                if (valid[i]) nValid++;
            }
        }

        double validMean(float[] a) {
            double sum = 0;
            for (int i = 0; i < k; i++) if (valid[i]) sum += a[i];
            return sum / nValid;
        }

        void pushE(double e) {
            if (wn == ewin.length) wsum -= ewin[wi]; else wn++;
            ewin[wi] = e;
            wi = (wi + 1) % ewin.length;
            wsum += e;
        }

        double avgE() {
            return wn < ewin.length ? 0 : wsum / wn;
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
        float[] a = f.amplitude();
        int k = a.length;
        double sum = 0;
        for (float v : a) sum += v;
        double mean = sum / k;
        if (Double.isNaN(mean) || Double.isInfinite(mean) || mean <= 1e-9) return;

        S s = sensors.get(f.sensorId());
        long seq = f.seq();
        long ts = f.timestampUs();

        if (s == null || s.k != k) {
            s = new S(k, cfg.shortWin());
            sensors.put(f.sensorId(), s);
        } else if (s.lastSeq >= 0) {
            boolean restarted = s.lastSeq - seq > cfg.backSeqTol() || s.lastTs - ts > cfg.backTsUs();
            if (restarted) {
                s = new S(k, cfg.shortWin());                // ESP32 khởi động lại: học lại, bỏ cả thời gian nghỉ cũ
                sensors.put(f.sensorId(), s);
            } else if (seq <= s.lastSeq || ts <= s.lastTs) {
                return;                                      // trùng hoặc lùi nhẹ: bỏ gói
            } else if (ts - s.lastTs > cfg.gapResetUs()) {
                S fresh = new S(k, cfg.shortWin());          // mất tín hiệu lâu: học lại, giữ thời gian nghỉ
                fresh.cooldownUntilUs = s.cooldownUntilUs;
                s = fresh;
                sensors.put(f.sensorId(), s);
            }
        }
        if (s.valid == null) {
            s.initMask(a, mean);
            if (s.nValid == 0) return;
        }
        mean = s.validMean(a);
        if (mean <= 1e-9) return;
        s.lastSeq = seq;
        s.lastTs = ts;
        s.frames++;

        // chuẩn hóa theo biên độ trung bình của khung, loại bỏ độ lợi AGC
        double e = 0;
        for (int i = 0; i < k; i++) {
            if (!s.valid[i]) {
                s.cur[i] = 0;
                continue;
            }
            s.cur[i] = a[i] / mean;
            if (s.hasPrev) e += Math.abs(s.cur[i] - s.prev[i]);
        }
        e /= s.nValid;
        System.arraycopy(s.cur, 0, s.prev, 0, k);
        s.hasPrev = true;
        s.pushE(e);
        if (s.frames == 1) System.arraycopy(s.cur, 0, s.base, 0, k);

        double d = 0;
        for (int i = 0; i < k; i++) if (s.valid[i]) d += Math.abs(s.cur[i] - s.base[i]);
        d /= s.nValid;

        if (s.frames <= cfg.warmupFrames()) {                // học đường nền
            learn(s, d, e, 0.02);
            s.recent = s.dNoise;
            return;
        }

        s.recentBefore = s.recent;
        s.recent = Math.max(d, s.recent * cfg.recentDecay());

        switch (s.phase) {
            case IDLE -> idle(s, d, e, ts);
            case BURST -> burst(s, d, ts);
            case QUIET -> quiet(f.sensorId(), s, ts);
        }
    }

    @Override
    public synchronized List<Event> poll() {
        List<Event> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    // ------------------------------------------------------------ chung

    private void learn(S s, double d, double e, double rate) {
        for (int i = 0; i < s.k; i++) s.base[i] += rate * (s.cur[i] - s.base[i]);
        s.dNoise += rate * (d - s.dNoise);
        s.eNoise += rate * (e - s.eNoise);
    }

    private double highThr(S s) {
        return Math.max(cfg.kHigh() * s.dNoise, cfg.minHigh());
    }

    private boolean agitated(S s) {
        return s.avgE() > cfg.agitK() * Math.max(s.eNoise, 1e-4);
    }

    /** Về IDLE với đường nền = khung hiện tại. */
    private void abandon(S s) {
        s.phase = Phase.IDLE;
        System.arraycopy(s.cur, 0, s.base, 0, s.k);
        s.highRun = 0;
        s.runPeak = 0;
    }

    // ------------------------------------------------------------- IDLE

    private void idle(S s, double d, double e, long ts) {
        double thr = highThr(s);
        if (d <= thr) {
            s.highRun = 0;
            s.runPeak = 0;
            learn(s, d, e, cfg.alpha());                     // chỉ học khi yên
            return;
        }

        if (s.highRun == 0) {
            s.runStartUs = ts;
            s.runPeak = 0;
            s.actAtRun = s.recentBefore;                     // đỉnh d gần đây, ngay trước khi lệch
        }
        s.highRun++;
        s.runPeak = Math.max(s.runPeak, d);

        if (s.highRun == cfg.onsetFrames() && ts >= s.cooldownUntilUs) {
            s.phase = Phase.BURST;
            s.burstStartUs = s.runStartUs;
            s.peak = s.runPeak;
            s.frozenThr = thr;
            s.frozenAct = Math.max(s.actAtRun, s.dNoise);
            s.inactiveRun = 0;
            return;
        }

        // lệch kéo dài mà không thành biến động (đang nghỉ, hoặc đổi môi trường): dựng lại đường nền
        if (ts - s.runStartUs > cfg.maxBurstUs()) abandon(s);
    }

    // ------------------------------------------------------------ BURST

    private void burst(S s, double d, long ts) {
        s.peak = Math.max(s.peak, d);
        boolean agit = agitated(s);
        boolean active = d > cfg.lowFactor() * s.frozenThr || agit;
        s.inactiveRun = active ? 0 : s.inactiveRun + 1;

        if (s.inactiveRun >= cfg.endFrames()) {
            endBurst(s);
        } else if (ts - s.burstStartUs > cfg.maxBurstUs()) {
            if (!agit) endBurst(s);                          // đã ổn định ở mức mới (ví dụ nằm xuống)
            else abandon(s);                                 // vẫn rung: đi lại liên tục
        }
    }

    private void endBurst(S s) {
        boolean bigEnough = s.peak >= cfg.peakMult() * s.frozenThr;
        boolean standsOut = s.peak >= cfg.peakVsRecent() * s.frozenAct;
        if (!bigEnough || !standsOut) {
            abandon(s);                                      // ngồi xuống, cúi nhặt đồ, đi bộ rồi dừng
            return;
        }
        s.phase = Phase.QUIET;
        s.quietStartUs = s.lastTs;
        s.qn = 0;
        java.util.Arrays.fill(s.qsum, 0);
        java.util.Arrays.fill(s.qsumsq, 0);
    }

    // ------------------------------------------------------------ QUIET

    private void quiet(int sensorId, S s, long ts) {
        s.qn++;
        for (int i = 0; i < s.k; i++) {
            s.qsum[i] += s.cur[i];
            s.qsumsq[i] += s.cur[i] * s.cur[i];
        }

        if (agitated(s)) {                                   // chuyển động trở lại trước khi đủ yên
            abandon(s);
            return;
        }
        if (ts - s.quietStartUs < cfg.quietUs()) return;

        double qstd = 0;
        for (int i = 0; i < s.k; i++) {
            if (!s.valid[i]) continue;
            double m = s.qsum[i] / s.qn;
            qstd += Math.sqrt(Math.max(0, s.qsumsq[i] / s.qn - m * m));
        }
        qstd /= s.nValid;
        double quietThr = Math.max(cfg.quietK() * s.dNoise, cfg.quietMin());

        if (qstd > quietThr) {
            abandon(s);
            return;
        }

        double ps = Math.min(1.0, s.peak / (3.0 * s.frozenThr));
        double qs = Math.max(0.0, 1.0 - qstd / quietThr);
        double conf = 0.5 + 0.5 * (0.6 * ps + 0.4 * qs);     // heuristic, không phải xác suất
        events.add(Event.fall(sensorId, epochSeconds.getAsLong(), Math.min(1.0, conf)));

        s.cooldownUntilUs = ts + cfg.cooldownUs();
        s.phase = Phase.IDLE;
        for (int i = 0; i < s.k; i++) s.base[i] = s.qsum[i] / s.qn;   // người nằm yên ở mức mới
        s.highRun = 0;
        s.runPeak = 0;
    }
}
