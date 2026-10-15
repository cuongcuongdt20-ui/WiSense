package vn.wisense.detect.fall;

import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.model.Event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

/** Kiểm thử FallDetector bằng tín hiệu giả, 100 gói/giây, 64 sóng mang con. */
class FallDetectorTest {

    private static final int PPS = 100;
    private static final int SUBCARRIERS = 64;

    /** Sinh luồng CSI: nền 20 + nhịp thở 0,25 Hz + nhiễu + phần "extra(t)" do từng kịch bản thêm vào. */
    private static List<Event> run(int sensorId, double seconds, DoubleUnaryOperator extra,
                                   FallDetector det, long seqStart, long tsStartUs) {
        Random rnd = new Random(42 + sensorId);
        List<Event> out = new ArrayList<>();
        int n = (int) (seconds * PPS);
        for (int i = 0; i < n; i++) {
            double t = i / (double) PPS;
            float[] amp = new float[SUBCARRIERS];
            double common = 20 + 0.5 * Math.sin(2 * Math.PI * 0.25 * t) + extra.applyAsDouble(t);
            for (int k = 0; k < SUBCARRIERS; k++) {
                amp[k] = (float) (common + (rnd.nextDouble() - 0.5));
            }
            det.onFrame(new CsiFrame(sensorId, seqStart + i, tsStartUs + i * 10_000L, -50, amp, new float[SUBCARRIERS]));
            out.addAll(det.poll());
        }
        return out;
    }

    private static List<Event> run(double seconds, DoubleUnaryOperator extra) {
        return run(1, seconds, extra, new FallDetector(), 0, 0);
    }

    /** Xung lớn trong [t0, t0+len) rồi trở lại như cũ. */
    private static DoubleUnaryOperator pulse(double t0, double len, double height) {
        return t -> (t >= t0 && t < t0 + len) ? height : 0.0;
    }

    @Test
    void phongTrongKhongBaoNgaVaKhongBaoNham() {
        assertEquals(0, run(120, t -> 0).size());
    }

    @Test
    void biendongLonRoiImLangThiBaoMotLanDungHopDong() {
        List<Event> ev = run(15, pulse(8.0, 0.5, 8.0));
        assertEquals(1, ev.size());
        Event e = ev.get(0);
        assertEquals("FALL", e.type());
        assertEquals(1, e.sensorId());
        assertTrue(e.num("confidence") > 0.5 && e.num("confidence") <= 1.0);
        assertTrue(e.toJson().startsWith("{\"t\":\"FALL\",\"sensor\":1,\"time\":"));
        assertTrue(e.toJson().contains("\"confidence\":"));
    }

    @Test
    void xungMoiBayMuoiGiayBaoDungTungLan() {
        // kịch bản giả lập Wokwi: xung 0,5 giây mỗi 30 giây
        List<Event> ev = run(100, t -> (t % 30.0 >= 10.0 && t % 30.0 < 10.5) ? 8.0 : 0.0);
        assertEquals(3, ev.size());
    }

    @Test
    void chiHaiKhungLonKhongDuBaKhungLienTiep() {
        assertEquals(0, run(15, pulse(8.0, 0.02, 8.0)).size());   // 2 khung
    }

    @Test
    void baKhungLienTiepLaDuBatDau() {
        assertEquals(1, run(15, pulse(8.0, 0.03, 8.0)).size());   // 3 khung
    }

    @Test
    void biendongRoiLaiCoChuyenDongTrongLucImLangThiKhongBao() {
        // ngã giả (cúi nhặt đồ): xung lớn, sau 1,5 giây lại cử động
        DoubleUnaryOperator s = t -> pulse(8.0, 0.5, 8.0).applyAsDouble(t)
                + pulse(10.0, 0.5, 6.0).applyAsDouble(t);
        assertEquals(0, run(20, s).size());
    }

    @Test
    void diBoLienTucKhongBaoNgaKeCaKhiDungLai() {
        // đi lại 12 giây (dao động ±4 ở 2 Hz), rồi đứng yên
        DoubleUnaryOperator walk = t -> (t >= 5 && t < 17) ? 4.0 * Math.sin(2 * Math.PI * 2.0 * t) : 0.0;
        assertEquals(0, run(30, walk).size());
    }

    @Test
    void ngoiXuongBienDoNhoKhongBaoNga() {
        // mức tín hiệu đổi nhẹ (2 đơn vị) trong 1 giây rồi yên ở mức mới
        DoubleUnaryOperator sit = t -> (t < 8.0) ? 0.0 : (t < 9.0 ? 2.5 * (t - 8.0) : 2.5);
        assertEquals(0, run(25, sit).size());
    }

    @Test
    void ngaRoiNamYenOMucMoiVanBao() {
        // sau cú ngã, mức tín hiệu ở lại khác trước (người nằm trên sàn)
        DoubleUnaryOperator fall = t -> t < 8.0 ? 0.0 : (t < 8.5 ? 9.0 : 4.0);
        assertEquals(1, run(20, fall).size());
    }

    @Test
    void haiCuNgaCachNhauDuoiNamGiayChiBaoMotLan() {
        DoubleUnaryOperator two = t -> pulse(8.0, 0.5, 8.0).applyAsDouble(t)
                + pulse(13.0, 0.5, 8.0).applyAsDouble(t);
        // cú đầu báo lúc ~11,5 giây; cú thứ hai (13 giây) nằm trong 5 giây nghỉ nên bị bỏ qua
        assertEquals(1, run(25, two).size());
    }

    @Test
    void haiCuNgaCachNhauDuLauBaoHaiLan() {
        DoubleUnaryOperator two = t -> pulse(8.0, 0.5, 8.0).applyAsDouble(t)
                + pulse(20.0, 0.5, 8.0).applyAsDouble(t);
        assertEquals(2, run(35, two).size());
    }

    @Test
    void cacCamBienKhongAnhHuongNhau() {
        FallDetector det = new FallDetector();
        List<Event> all = new ArrayList<>();
        all.addAll(run(1, 15, pulse(8.0, 0.5, 8.0), det, 0, 0));
        all.addAll(run(2, 15, t -> 0, det, 0, 0));
        assertEquals(1, all.size());
        assertEquals(1, all.get(0).sensorId());
    }

    @Test
    void boQuaGoiTrungVaSaiThuTu() {
        FallDetector det = new FallDetector();
        float[] amp = new float[SUBCARRIERS];
        java.util.Arrays.fill(amp, 20f);
        det.onFrame(new CsiFrame(1, 10, 100_000, -50, amp, null));
        det.onFrame(new CsiFrame(1, 10, 110_000, -50, amp, null));   // trùng seq
        det.onFrame(new CsiFrame(1, 9, 120_000, -50, amp, null));    // cũ hơn
        assertEquals(0, det.poll().size());
    }

    @Test
    void matGoiLauThiHocLaiVaKhongBaoSai() {
        FallDetector det = new FallDetector();
        List<Event> ev = new ArrayList<>(run(1, 6, t -> 0, det, 0, 0));
        // mất 10 giây, quay lại ở mức tín hiệu khác hẳn: không được coi là biến động
        ev.addAll(run(1, 12, t -> 7.0, det, 10_000, 16_000_000L));
        assertEquals(0, ev.size());
    }

    @Test
    void doTreVaSoKhungRaSuKienDungThoiGianTheoGoi() {
        FallDetector det = new FallDetector(FallDetector.Config.defaults(), () -> 1_739_000_000L);
        List<Event> ev = run(1, 15, pulse(8.0, 0.5, 8.0), det, 0, 0);
        assertEquals(1_739_000_000L, (long) ev.get(0).num("time"));
    }
}
