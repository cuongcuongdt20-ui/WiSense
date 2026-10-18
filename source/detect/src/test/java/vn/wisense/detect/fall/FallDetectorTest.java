package vn.wisense.detect.fall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;
import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.model.Event;

/**
 * Kiểm thử FallDetector bằng CSI giả, 100 gói/giây, 64 sóng mang con.
 *
 * <p>Mô hình tín hiệu sát thực tế hơn bản đầu: mỗi sóng mang con có biên độ nền riêng, chuyển động làm
 * các sóng mang con đổi theo hướng khác nhau (mẫu có trung bình bằng 0, nên biên độ trung bình gần như
 * không đổi), và AGC nhân cả khung với một độ lợi chung. Một bộ phát hiện chỉ nhìn biên độ trung bình
 * sẽ không qua được các bài này.
 */
class FallDetectorTest {

    private static final int PPS = 100;
    private static final int K = 64;

    /** Biên độ nền riêng từng sóng mang con (khoảng 12–28). */
    private static final double[] PROFILE = new double[K];
    /** Mẫu thay đổi khi có chuyển động: trung bình 0, độ lớn tuyệt đối trung bình 1. */
    private static final double[] PATTERN = new double[K];
    /** Mẫu rất nhỏ cho nhịp thở. */
    private static final double[] BREATH = new double[K];
    /** Pha riêng từng sóng mang con cho chuyển động dao động (đi lại). */
    private static final double[] PHASE = new double[K];

    static {
        Random r = new Random(7);
        double sum = 0;
        for (int k = 0; k < K; k++) {
            PROFILE[k] = 20 * (1 + 0.4 * Math.sin(0.37 * k));
            PATTERN[k] = r.nextDouble() * 2 - 1;
            BREATH[k] = r.nextDouble() * 2 - 1;
            PHASE[k] = r.nextDouble() * 2 * Math.PI;
            sum += PATTERN[k];
        }
        double mean = sum / K, abs = 0;
        for (int k = 0; k < K; k++) {
            PATTERN[k] -= mean;
            abs += Math.abs(PATTERN[k]);
        }
        for (int k = 0; k < K; k++) PATTERN[k] /= abs / K;
    }

    /** Thay đổi tương đối của sóng mang con k tại thời điểm t (giây). */
    private interface Motion {
        double at(double t, int k);
    }

    private static Motion none() {
        return (t, k) -> 0;
    }

    /** Xung trong [t0, t0+len): mọi sóng mang con đổi theo mẫu PATTERN, rồi trở lại như cũ. */
    private static Motion pulse(double t0, double len, double h) {
        return (t, k) -> (t >= t0 && t < t0 + len) ? h * PATTERN[k] : 0;
    }

    /** Đổi mức: trước t0 bằng 0, từ t0 trong 'ramp' giây tăng dần tới h rồi giữ nguyên. */
    private static Motion shift(double t0, double ramp, double h) {
        return (t, k) -> t < t0 ? 0 : h * PATTERN[k] * Math.min(1.0, (t - t0) / ramp);
    }

    /** Đi lại: dao động hz Hz trong [t0, t1), mỗi sóng mang con một pha. */
    private static Motion walking(double t0, double t1, double amp, double hz) {
        return (t, k) -> (t >= t0 && t < t1) ? amp * Math.sin(2 * Math.PI * hz * t + PHASE[k]) : 0;
    }

    private static Motion sum(Motion... ms) {
        return (t, k) -> {
            double s = 0;
            for (Motion m : ms) s += m.at(t, k);
            return s;
        };
    }

    private static List<Event> run(int sensorId, double seconds, Motion motion, DoubleUnaryOperator gain,
                                   FallDetector det, long seqStart, long tsStartUs) {
        Random rnd = new Random(42 + sensorId);
        List<Event> out = new ArrayList<>();
        int n = (int) (seconds * PPS);
        for (int i = 0; i < n; i++) {
            double t = i / (double) PPS;
            double g = gain.applyAsDouble(t);
            double breath = 0.01 * Math.sin(2 * Math.PI * 0.25 * t);
            float[] amp = new float[K];
            for (int k = 0; k < K; k++) {
                double rel = Math.max(0.0, 1 + breath * BREATH[k] + motion.at(t, k));
                amp[k] = (float) (g * PROFILE[k] * rel + (rnd.nextDouble() - 0.5) * 0.5);
            }
            det.onFrame(new CsiFrame(sensorId, seqStart + i, tsStartUs + i * 10_000L, -50, amp, new float[K]));
            out.addAll(det.poll());
        }
        return out;
    }

    private static List<Event> run(double seconds, Motion motion) {
        return run(1, seconds, motion, t -> 1.0, new FallDetector(), 0, 0);
    }

    private static List<Event> run(double seconds, Motion motion, DoubleUnaryOperator gain) {
        return run(1, seconds, motion, gain, new FallDetector(), 0, 0);
    }

    // ------------------------------------------------------------ cơ bản

    @Test
    void phongTrongKhongBaoNgaVaKhongBaoNham() {
        assertEquals(0, run(120, none()).size());
    }

    @Test
    void biendongLonRoiImLangThiBaoMotLanDungHopDong() {
        List<Event> ev = run(15, pulse(8.0, 0.5, 0.3));
        assertEquals(1, ev.size());
        Event e = ev.get(0);
        assertEquals("FALL", e.type());
        assertEquals(1, e.sensorId());
        assertTrue(e.num("confidence") > 0.5 && e.num("confidence") <= 1.0);
        assertTrue(e.toJson().startsWith("{\"t\":\"FALL\",\"sensor\":1,\"time\":"));
        assertTrue(e.toJson().contains("\"confidence\":"));
    }

    @Test
    void xungMoiBaMuoiGiayBaoDungTungLan() {
        // kịch bản giả lập Wokwi: xung 0,5 giây mỗi 30 giây
        Motion m = (t, k) -> (t % 30.0 >= 10.0 && t % 30.0 < 10.5) ? 0.3 * PATTERN[k] : 0;
        assertEquals(3, run(100, m).size());
    }

    @Test
    void chiHaiKhungLonKhongDuBaKhungLienTiep() {
        assertEquals(0, run(15, pulse(8.0, 0.02, 0.3)).size());
    }

    @Test
    void baKhungLienTiepLaDuBatDau() {
        assertEquals(1, run(15, pulse(8.0, 0.03, 0.3)).size());
    }

    @Test
    void biendongRoiLaiCoChuyenDongTrongLucImLangThiKhongBao() {
        // cúi nhặt đồ: xung lớn, sau 1,5 giây lại cử động
        assertEquals(0, run(20, sum(pulse(8.0, 0.5, 0.3), pulse(10.0, 0.5, 0.2))).size());
    }

    @Test
    void diBoLienTucKhongBaoNgaKeCaKhiDungLai() {
        assertEquals(0, run(30, walking(5, 17, 0.3, 2.0)).size());
    }

    @Test
    void ngoiXuongBienDoNhoKhongBaoNga() {
        assertEquals(0, run(25, shift(8.0, 1.0, 0.09)).size());
    }

    @Test
    void ngaRoiNamYenOMucMoiVanBao() {
        // sau cú ngã, hình dạng CSI ở lại khác trước (người nằm trên sàn)
        Motion fall = (t, k) -> t < 8.0 ? 0 : (t < 8.5 ? 0.3 * PATTERN[k] : 0.12 * PATTERN[k]);
        assertEquals(1, run(20, fall).size());
    }

    @Test
    void haiCuNgaCachNhauDuoiNamGiayChiBaoMotLan() {
        // cú đầu báo lúc ~11,5 giây; cú thứ hai (13 giây) nằm trong 5 giây nghỉ nên bị bỏ qua
        assertEquals(1, run(25, sum(pulse(8.0, 0.5, 0.3), pulse(13.0, 0.5, 0.3))).size());
    }

    @Test
    void haiCuNgaCachNhauDuLauBaoHaiLan() {
        assertEquals(2, run(35, sum(pulse(8.0, 0.5, 0.3), pulse(20.0, 0.5, 0.3))).size());
    }

    // ------------------------------------------------------- thực tế hơn

    @Test
    void mauChuyenDongCoTrungBinhBangKhong() {
        double s = 0;
        for (double v : PATTERN) s += v;
        assertTrue(Math.abs(s / K) < 1e-9, "biên độ trung bình không đổi, bộ phát hiện phải nhìn từng sóng mang con");
    }

    @Test
    void nga_chuyenDongLechPhaVanBatDuocDuTrungBinhKhongDoi() {
        assertEquals(1, run(15, pulse(8.0, 0.5, 0.3)).size());
    }

    @Test
    void doiMucAgcKhongBaoNham() {
        // độ lợi nhảy 1,0 -> 1,5 -> 0,7 -> 1,0, không có ai ngã
        DoubleUnaryOperator agc = t -> t < 8 ? 1.0 : (t < 14 ? 1.5 : (t < 20 ? 0.7 : 1.0));
        assertEquals(0, run(30, none(), agc).size());
    }

    @Test
    void doiMucAgcRoiYenKhongBaoNhamKeCaKhiNhanDoiNhanVoiHeSoLon() {
        DoubleUnaryOperator agc = t -> t < 8 ? 1.0 : 3.0;
        assertEquals(0, run(25, none(), agc).size());
    }

    @Test
    void ngaDungLucAgcDoiMucVanBaoDung() {
        DoubleUnaryOperator agc = t -> t < 8.2 ? 1.0 : 1.5;
        assertEquals(1, run(20, pulse(8.0, 0.5, 0.3), agc).size());
    }

    @Test
    void dangDiRoiNgaVanBat() {
        // đi lại 5–12 giây, đến 12,5 giây ngã mạnh hơn hẳn rồi nằm yên
        Motion m = sum(walking(5, 12.5, 0.3, 2.0), pulse(12.5, 0.5, 0.7));
        assertEquals(1, run(25, m).size());
    }

    @Test
    void diBoRoiDungLaiKhongBaoNga() {
        assertEquals(0, run(25, walking(5, 12.5, 0.3, 2.0)).size());
    }

    @Test
    void diBoNgatQuangRoiDungLaiKhongBaoNga() {
        // bước đi có quãng nghỉ ngắn giữa các đợt; đợt cuối kết thúc rồi đứng yên 3 giây
        Motion m = sum(walking(5.0, 6.2, 0.3, 2.0), walking(6.7, 7.9, 0.3, 2.0), walking(8.4, 9.6, 0.3, 2.0));
        assertEquals(0, run(25, m).size());
    }

    @Test
    void diBoNgatQuangRoiNgaManhHonHanVanBat() {
        Motion m = sum(walking(5.0, 6.2, 0.3, 2.0), walking(6.7, 7.9, 0.3, 2.0), walking(8.4, 9.6, 0.3, 2.0),
                pulse(10.0, 0.5, 0.9));
        assertEquals(1, run(25, m).size());
    }

    // ------------------------------------- khớp với firmware giả lập Wokwi (TV1)

    /**
     * Tái hiện firmware/wisense-sim/main/main.c: chu kỳ 60 giây (phòng trống, đi lại 20–32 s, ngồi thở,
     * ngã lúc 40 s, nằm yên), sóng mang con rỗng của LLTF bằng 0, CSI lượng tử hóa int8 (imag, real) và
     * server tính biên độ = sqrt(imag² + real²).
     */
    @Test
    void kichBanWokwiMoiChuKyBaoDungMotCuNgaVaKhongBaoKhiDiLai() {
        final int n = 64;
        long lcg = 7;
        double[] profile = new double[n], pattern = new double[n], breath = new double[n], phase = new double[n];
        double sum = 0, abs = 0;
        for (int k = 0; k < n; k++) {
            profile[k] = 20.0 * (1.0 + 0.4 * Math.sin(0.37 * k));
            lcg = (lcg * 1664525L + 1013904223L) & 0xFFFFFFFFL;
            pattern[k] = (lcg >> 8) / 16777216.0 * 2 - 1;
            lcg = (lcg * 1664525L + 1013904223L) & 0xFFFFFFFFL;
            breath[k] = (lcg >> 8) / 16777216.0 * 2 - 1;
            lcg = (lcg * 1664525L + 1013904223L) & 0xFFFFFFFFL;
            phase[k] = (lcg >> 8) / 16777216.0 * 2 * Math.PI;
            sum += pattern[k];
        }
        for (int k = 0; k < n; k++) {
            pattern[k] -= sum / n;
            abs += Math.abs(pattern[k]);
        }
        for (int k = 0; k < n; k++) pattern[k] /= abs / n;

        FallDetector det = new FallDetector();
        Random rnd = new Random(5);
        List<Integer> fallFrames = new ArrayList<>();
        int frames = 125 * PPS;
        for (int i = 0; i < frames; i++) {
            double t = (i / (double) PPS) % 60.0;
            float[] amp = new float[n];
            for (int k = 0; k < n; k++) {
                boolean isNull = k == 0 || (k >= 27 && k <= 37);
                double a = 0;
                if (!isNull) {
                    double m = 0;
                    if (t >= 20 && t < 32) m += 0.3 * Math.sin(2 * Math.PI * 2.0 * t + phase[k]);
                    if (t >= 32) m += 0.02 * breath[k] * Math.sin(2 * Math.PI * 0.25 * t);
                    if (t >= 40 && t < 40.5) m += 0.4 * pattern[k];
                    double rel = 1.0 + m;
                    a = profile[k] * (rel > 0 ? rel : 0) + (rnd.nextInt(1000) / 1000.0 - 0.5) * 0.5;
                }
                int im = (int) Math.max(-128, Math.min(127, Math.round(a * Math.sin(0.1 * k))));
                int re = (int) Math.max(-128, Math.min(127, Math.round(a * Math.cos(0.1 * k))));
                amp[k] = (float) Math.sqrt((double) im * im + (double) re * re);
            }
            det.onFrame(new CsiFrame(1, i, i * 10_000L, -40, amp, new float[n]));
            if (!det.poll().isEmpty()) fallFrames.add(i);
        }
        assertEquals(2, fallFrames.size());
        // ngã lúc 40 s và 100 s, báo sau ~3 giây im lặng
        assertTrue(fallFrames.get(0) > 4300 && fallFrames.get(0) < 4500, "cú ngã thứ nhất báo lúc " + fallFrames.get(0));
        assertTrue(fallFrames.get(1) > 10300 && fallFrames.get(1) < 10500, "cú ngã thứ hai báo lúc " + fallFrames.get(1));
    }

    // ------------------------------------------------- mạng và nhiều cảm biến

    @Test
    void cacCamBienKhongAnhHuongNhau() {
        FallDetector det = new FallDetector();
        List<Event> all = new ArrayList<>();
        all.addAll(run(1, 15, pulse(8.0, 0.5, 0.3), t -> 1.0, det, 0, 0));
        all.addAll(run(2, 15, none(), t -> 1.0, det, 0, 0));
        assertEquals(1, all.size());
        assertEquals(1, all.get(0).sensorId());
    }

    @Test
    void boQuaGoiTrungVaLuiNhe() {
        FallDetector det = new FallDetector();
        float[] amp = new float[K];
        java.util.Arrays.fill(amp, 20f);
        det.onFrame(new CsiFrame(1, 10, 100_000, -50, amp, null));
        det.onFrame(new CsiFrame(1, 10, 110_000, -50, amp, null));   // trùng seq
        det.onFrame(new CsiFrame(1, 9, 120_000, -50, amp, null));    // cũ hơn vài gói
        assertEquals(0, det.poll().size());
    }

    @Test
    void esp32KhoiDongLaiSeqVaTimestampVeKhongThiVanTiepTucPhatHien() {
        FallDetector det = new FallDetector();
        List<Event> ev = new ArrayList<>(run(1, 10, none(), t -> 1.0, det, 5_000, 50_000_000L));
        assertEquals(0, ev.size());
        // ESP32 reset: seq và timestamp bắt đầu lại từ 0; sau đó có một cú ngã
        ev.addAll(run(1, 15, pulse(8.0, 0.5, 0.3), t -> 1.0, det, 0, 0));
        assertEquals(1, ev.size());
    }

    @Test
    void chiResetSeqNhungTimestampKhongLuiVanHocLai() {
        FallDetector det = new FallDetector();
        run(1, 10, none(), t -> 1.0, det, 5_000, 0);
        List<Event> ev = run(1, 15, pulse(8.0, 0.5, 0.3), t -> 1.0, det, 0, 10_000_000L);
        assertEquals(1, ev.size());
    }

    @Test
    void matGoiLauThiHocLaiVaKhongBaoSai() {
        FallDetector det = new FallDetector();
        List<Event> ev = new ArrayList<>(run(1, 6, none(), t -> 1.0, det, 0, 0));
        // mất 10 giây, quay lại với độ lợi khác hẳn: không được coi là biến động
        ev.addAll(run(1, 12, none(), t -> 1.8, det, 10_000, 16_000_000L));
        assertEquals(0, ev.size());
    }

    @Test
    void thoiGianSuKienLayTuDongHoCuaHeThong() {
        FallDetector det = new FallDetector(FallDetector.Config.defaults(), () -> 1_739_000_000L);
        List<Event> ev = run(1, 15, pulse(8.0, 0.5, 0.3), t -> 1.0, det, 0, 0);
        assertEquals(1_739_000_000L, (long) ev.get(0).num("time"));
    }
}
