
package vn.wisense.detect.breathing;

import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.model.Detector;
import vn.wisense.common.model.Event;

import org.jtransforms.fft.DoubleFFT_1D;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BreathingEstimator implements Detector {

    private static final int N_FFT = 4096;
    private static final double F_LO = 0.15;
    private static final double F_HI = 0.5;
    private static final double MIN_SNR = 3.0;
    private static final long MIN_DATA_US = 30_000_000L;
    private static final long ESTIMATE_INTERVAL_US = 5_000_000L;

    public record Config(double windowSec, double fs) {
        public Config {
            if (!Double.isFinite(windowSec) || windowSec < 30.0) {
                throw new IllegalArgumentException(
                        "windowSec must be at least 30 seconds");
            }
            if (!Double.isFinite(fs) || fs <= 0
                    || F_HI >= fs / 2.0) {
                throw new IllegalArgumentException(
                        "fs must be positive and above the Nyquist requirement");
            }
        }

        public static Config defaults() {
            return new Config(60.0, 10.0);
        }
    }

    private static final class S {
        long lastSeq = -1;
        long lastTs = -1;
        long lastEstimateUs;
        boolean hasEstimated;

        // Mỗi phần tử: {timestampUs, giá trị}
        final ArrayDeque<double[]> buf = new ArrayDeque<>();
    }

    record SpectralEstimate(double bpm, double snr) {}

    private final Config cfg;

    /** Ngưỡng SNR có thể cập nhật khi detector đang chạy. */
    private volatile double configuredMinSnr = MIN_SNR;

    private final Map<Integer, S> sensors = new HashMap<>();
    private final List<Event> events = new ArrayList<>();

    public BreathingEstimator() {
        this(Config.defaults());
    }

    public BreathingEstimator(Config cfg) {
        if (cfg == null) {
            throw new IllegalArgumentException("cfg must not be null");
        }
        this.cfg = cfg;
    }

    @Override
    public synchronized void onFrame(CsiFrame f) {
        if (f == null || f.amplitude() == null
                || f.amplitude().length == 0) {
            return;
        }

        S s = sensors.computeIfAbsent(f.sensorId(), id -> new S());
        long ts = f.timestampUs();

        // ESP32 có thể khởi động lại, làm seq/timestamp quay về thấp.
        if (s.lastSeq >= 0) {
            boolean seqReset = f.seq() < s.lastSeq
                    && s.lastSeq - f.seq() > 100;

            boolean timestampReset = ts < s.lastTs
                    && s.lastTs - ts > 1_000_000L;

            if (seqReset || timestampReset) {
                s.buf.clear();
                s.hasEstimated = false;
                s.lastEstimateUs = 0;
                s.lastSeq = -1;
                s.lastTs = -1;
            } else if (f.seq() <= s.lastSeq || ts <= s.lastTs) {
                // Gói trùng hoặc sai thứ tự thông thường.
                return;
            }
        }

        s.lastSeq = f.seq();
        s.lastTs = ts;

        s.buf.addLast(new double[]{ts, meanAmp(f.amplitude())});

        long cutoff = ts - (long) (cfg.windowSec() * 1e6);
        while (!s.buf.isEmpty() && s.buf.peekFirst()[0] < cutoff) {
            s.buf.removeFirst();
        }

        if (s.buf.size() < 2) {
            return;
        }

        // Phải có ít nhất 30 giây dữ liệu thực, không chỉ zero-padding.
        double[] first = s.buf.peekFirst();
        double[] last = s.buf.peekLast();

        if (last[0] - first[0] < MIN_DATA_US) {
            return;
        }

        // Không tính lại thường xuyên hơn mỗi 5 giây.
        if (s.hasEstimated
                && ts - s.lastEstimateUs < ESTIMATE_INTERVAL_US) {
            return;
        }

        s.hasEstimated = true;
        s.lastEstimateUs = ts;

        double[][] pts = s.buf.toArray(new double[0][]);
        double[] x = resample(pts, cfg.fs());

        SpectralEstimate estimate = analyze(x, cfg.fs());

        if (estimate.bpm() > 0
                && estimate.snr() >= configuredMinSnr) {
            events.add(Event.breath(
                    f.sensorId(), estimate.bpm(), estimate.snr()));
        }
    }

    @Override
    public synchronized void configure(String key, double value) {
        if (!"breathMinSnr".equals(key)) {
            throw new IllegalArgumentException(
                    "Unsupported breathing config key: " + key);
        }

        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(
                    "breathMinSnr must be finite and greater than 0");
        }

        configuredMinSnr = value;

        System.getLogger(BreathingEstimator.class.getName()).log(
                System.Logger.Level.INFO,
                "Breathing minimum SNR updated: " + value);
    }

    @Override
    public synchronized List<Event> poll() {
        List<Event> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    /** Trung bình biên độ, bỏ các sóng mang con null hoặc gần bằng 0. */
    static double meanAmp(float[] a) {
        if (a == null || a.length == 0) {
            return 0;
        }

        double sum = 0;
        int used = 0;

        for (float v : a) {
            if (Float.isFinite(v) && v > 1e-3f) {
                sum += v;
                used++;
            }
        }

        return used == 0 ? 0 : sum / used;
    }

    /**
     * Nội suy tuyến tính lên lưới đều.
     * pts gồm các cặp {timestampUs, value}, đã sắp theo thời gian.
     */
    static double[] resample(double[][] pts, double fs) {
        if (pts == null || pts.length < 2
                || !Double.isFinite(fs) || fs <= 0) {
            return new double[0];
        }

        double t0 = pts[0][0];
        double t1 = pts[pts.length - 1][0];

        if (!Double.isFinite(t0) || !Double.isFinite(t1) || t1 <= t0) {
            return new double[0];
        }

        double sampleCount = (t1 - t0) / 1e6 * fs;

        if (!Double.isFinite(sampleCount)
                || sampleCount < 1
                || sampleCount > Integer.MAX_VALUE) {
            return new double[0];
        }

        int n = (int) sampleCount;
        double[] x = new double[n];
        int j = 0;

        for (int i = 0; i < n; i++) {
            double t = t0 + i * 1e6 / fs;

            while (j < pts.length - 2 && pts[j + 1][0] < t) {
                j++;
            }

            double ta = pts[j][0];
            double tb = pts[j + 1][0];

            double w = tb > ta ? (t - ta) / (tb - ta) : 0;
            w = Math.max(0, Math.min(1, w));

            x[i] = pts[j][1] * (1 - w) + pts[j + 1][1] * w;
        }

        return x;
    }

    /** Giữ API cũ cho các bài test và nơi đang sử dụng peakBpm. */
    static double peakBpm(double[] x, double fs) {
        return analyze(x, fs).bpm();
    }

    /**
     * Tìm đỉnh phổ trong dải 0.15–0.5 Hz.
     * SNR = công suất đỉnh / trung vị công suất trong dải.
     */
    static SpectralEstimate analyze(double[] x, double fs) {
        if (x == null || x.length < 3
                || !Double.isFinite(fs) || fs <= 0
                || F_HI >= fs / 2.0) {
            return new SpectralEstimate(0, 0);
        }

        // Nếu đầu vào dài hơn FFT, chỉ sử dụng tối đa N_FFT mẫu cuối.
        int n = Math.min(x.length, N_FFT);
        int offset = x.length - n;

        double meanT = (n - 1) / 2.0;
        double meanY = 0;

        for (int i = 0; i < n; i++) {
            double v = x[offset + i];
            if (!Double.isFinite(v)) {
                return new SpectralEstimate(0, 0);
            }
            meanY += v;
        }
        meanY /= n;

        // Hồi quy tuyến tính bình phương tối thiểu: y = intercept + slope*t.
        // Trừ cả mức trung bình và xu hướng trôi chậm.
        double numerator = 0;
        double denominator = 0;

        for (int i = 0; i < n; i++) {
            double t = i - meanT;
            numerator += t * (x[offset + i] - meanY);
            denominator += t * t;
        }

        double slope = denominator > 0 ? numerator / denominator : 0;

        double[] detrended = new double[n];
        double energy = 0;

        for (int i = 0; i < n; i++) {
            double trend = meanY + slope * (i - meanT);
            detrended[i] = x[offset + i] - trend;
            energy += detrended[i] * detrended[i];
        }

        // Tránh nhận nhiễu số học từ tín hiệu hằng là một nhịp thở.
        double scale = Math.max(1.0, meanY * meanY);
        if (!Double.isFinite(energy) || energy / n <= 1e-12 * scale) {
            return new SpectralEstimate(0, 0);
        }

        double[] a = new double[N_FFT];

        for (int i = 0; i < n; i++) {
            double hann = 0.5 * (1 - Math.cos(
                    2 * Math.PI * i / (n - 1)));
            a[i] = detrended[i] * hann;
        }

        new DoubleFFT_1D(N_FFT).realForward(a);

        int kLo = Math.max(1, (int) Math.ceil(F_LO * N_FFT / fs));
        int kHi = Math.min(N_FFT / 2 - 1,
                (int) Math.floor(F_HI * N_FFT / fs));

        if (kLo > kHi) {
            return new SpectralEstimate(0, 0);
        }

        double[] powers = new double[kHi - kLo + 1];
        double best = -1;
        int peak = kLo;

        for (int k = kLo; k <= kHi; k++) {
            double re = a[2 * k];
            double im = a[2 * k + 1];
            double p = re * re + im * im;

            powers[k - kLo] = p;

            if (p > best) {
                best = p;
                peak = k;
            }
        }

        Arrays.sort(powers);
        int m = powers.length;
        double median = m % 2 == 1
                ? powers[m / 2]
                : (powers[m / 2 - 1] + powers[m / 2]) / 2.0;

        if (!Double.isFinite(best) || best <= 0) {
            return new SpectralEstimate(0, 0);
        }

        double snr = median > 0
                ? best / median
                : 1_000_000.0;

        // Tránh giá trị vô hạn hoặc quá lớn trong JSON.
        snr = Math.min(1_000_000.0, snr);

        double bpm = peak * fs / N_FFT * 60.0;

        return new SpectralEstimate(bpm, snr);
    }
}
