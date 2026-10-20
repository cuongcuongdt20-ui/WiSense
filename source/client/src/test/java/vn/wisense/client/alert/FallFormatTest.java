package vn.wisense.client.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class FallFormatTest {

    @Test
    void dongCanhBaoCoGioCamBienVaDoTinCay() {
        assertEquals("07:33:20 08/02/2025 · cảm biến 1 · tin cậy 80%",
                FallFormat.line(1, 1739000000L, 0.8, ZoneId.of("UTC")));
    }

    @Test
    void gioTheoMuiGioDuocChon() {
        assertEquals("14:33:20 08/02/2025 · cảm biến 2 · tin cậy 87%",
                FallFormat.line(2, 1739000000L, 0.872, ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    @Test
    void doTinCayNgoaiKhoangBiCat() {
        assertEquals("0%", FallFormat.percent(-0.3));
        assertEquals("100%", FallFormat.percent(1.7));
        assertEquals("0%", FallFormat.percent(Double.NaN));
    }
}