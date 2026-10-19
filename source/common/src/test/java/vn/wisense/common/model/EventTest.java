package vn.wisense.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EventTest {
    @Test
    void jsonDungHopDong() {
        assertEquals("{\"t\":\"FALL\",\"sensor\":1,\"time\":1739000000,\"confidence\":0.8}",
                Event.fall(1, 1739000000L, 0.8).toJson());
        assertEquals("{\"t\":\"MOTION\",\"sensor\":1,\"level\":0.73,\"moving\":true}",
                Event.motion(1, 0.73, true).toJson());
        assertEquals("{\"t\":\"BREATH\",\"sensor\":1,\"bpm\":15.2,\"snr\":4.1}",
                Event.breath(1, 15.2, 4.1).toJson());
        assertEquals("{\"t\":\"STATS\",\"sensor\":1,\"pps\":98,\"loss\":0.02}",
                Event.stats(1, 98, 0.02).toJson());
    }

    @Test
    void signalInMangDungKhongPhaiDiaChi() {
        String json = Event.signal(1, new float[] {20.5f, 21f, 19.254f}).toJson();
        assertEquals("{\"t\":\"SIGNAL\",\"sensor\":1,\"amp\":[20.5,21.0,19.25]}", json);
        assertEquals("{\"t\":\"SIGNAL\",\"sensor\":2,\"amp\":[]}", Event.signal(2, new float[0]).toJson());
    }

    @Test
    void lamTronHaiChuSoVaKhongSinhNaN() {
        assertEquals("{\"t\":\"FALL\",\"sensor\":2,\"time\":5,\"confidence\":0.87}",
                Event.fall(2, 5L, 0.8712345).toJson());
        assertEquals("{\"t\":\"BREATH\",\"sensor\":3,\"bpm\":0.0,\"snr\":0.0}",
                Event.breath(3, Double.NaN, Double.POSITIVE_INFINITY).toJson());
    }
}
