package vn.wisense.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class EventTest {

    /** Bỏ trường ts ở cuối để so JSON cố định (ts là giờ hệ thống nên mỗi lần một khác). */
    private static String withoutTs(String json) {
        return json.replaceAll(",\"ts\":\\d+\\}$", "}");
    }

    @Test
    void jsonDungHopDong() {
        assertEquals("{\"t\":\"MOTION\",\"sensor\":1,\"level\":0.73,\"moving\":true}",
                withoutTs(Event.motion(1, 0.73, true).toJson()));
        assertEquals("{\"t\":\"BREATH\",\"sensor\":1,\"bpm\":15.2,\"snr\":4.1}",
                withoutTs(Event.breath(1, 15.2, 4.1).toJson()));
        assertEquals("{\"t\":\"STATS\",\"sensor\":1,\"pps\":98,\"loss\":0.02}",
                withoutTs(Event.stats(1, 98, 0.02).toJson()));
    }

    @Test
    void fallCoIdVaTsODuoiCung() {
        assertEquals("{\"t\":\"FALL\",\"sensor\":1,\"time\":1739000000,\"confidence\":0.8,\"id\":42,\"ts\":1739000000123}",
                Event.fall(1, 1739000000L, 0.8, 42L, 1739000000123L).toJson());
    }

    @Test
    void signalInMangDungKhongPhaiDiaChi() {
        assertEquals("{\"t\":\"SIGNAL\",\"sensor\":1,\"amp\":[20.5,21.0,19.25]}",
                withoutTs(Event.signal(1, new float[] {20.5f, 21f, 19.254f}).toJson()));
        assertEquals("{\"t\":\"SIGNAL\",\"sensor\":2,\"amp\":[]}", withoutTs(Event.signal(2, new float[0]).toJson()));
    }

    @Test
    void lamTronHaiChuSoVaKhongSinhNaN() {
        assertEquals("{\"t\":\"FALL\",\"sensor\":2,\"time\":5,\"confidence\":0.87,\"id\":1,\"ts\":9}",
                Event.fall(2, 5L, 0.8712345, 1L, 9L).toJson());
        assertEquals("{\"t\":\"BREATH\",\"sensor\":3,\"bpm\":0.0,\"snr\":0.0}",
                withoutTs(Event.breath(3, Double.NaN, Double.POSITIVE_INFINITY).toJson()));
    }

    @Test
    void moiEventDeuCoTsLaLucTao() {
        long before = System.currentTimeMillis();
        List<Event> all = List.of(Event.fall(1, 1L, 0.9), Event.motion(1, 0.5, false), Event.breath(1, 15, 4),
                Event.stats(1, 100, 0), Event.signal(1, new float[] {1f}));
        long after = System.currentTimeMillis();
        for (Event e : all) {
            assertTrue(e.ts() >= before && e.ts() <= after, e.type() + " thiếu ts hợp lệ");
            assertTrue(e.toJson().contains("\"ts\":" + e.ts()));
        }
    }

    @Test
    void eventTaoTrucTiepBangConstructorCungCoTs() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("x", 1);
        Event e = new Event("CUSTOM", 7, f);
        assertTrue(e.ts() > 0);
        assertEquals(-1L, e.id());
        // sao chép Event giữ nguyên ts cũ
        Event copy = new Event(e.type(), e.sensorId(), e.fields());
        assertEquals(e.ts(), copy.ts());
    }

    @Test
    void idCuaFallTangDanVaKhongTrungKhiNhieuLuongCungTao() throws Exception {
        Event a = Event.fall(1, 1L, 0.9);
        Event b = Event.fall(1, 1L, 0.9);
        assertTrue(a.id() >= 1 && b.id() > a.id());
        assertEquals(-1L, Event.motion(1, 0.1, false).id());

        Set<Long> ids = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            fs.add(pool.submit(() -> {
                for (int i = 0; i < 1000; i++) ids.add(Event.fall(1, 1L, 0.9).id());
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        assertEquals(8000, ids.size());
    }
}