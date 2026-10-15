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
    }

    @Test
    void lamTronHaiChuSo() {
        assertEquals("{\"t\":\"FALL\",\"sensor\":2,\"time\":5,\"confidence\":0.87}",
                Event.fall(2, 5L, 0.8712345).toJson());
    }
}
