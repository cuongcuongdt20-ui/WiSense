package vn.wisense.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EspCsiConvertTest {
    private static final String HEADER = "type,seq,timestamp,taget_seq,taget,mac,rssi,rate,sig_mode,mcs,cwb,smoothing,"
            + "not_sounding,aggregation,stbc,fec_coding,sgi,noise_floor,ampdu_cnt,channel_primary,channel_secondary,"
            + "local_timestamp,ant,sig_len,rx_state,len,first_word_invalid,data";

    /** Một dòng CSI_DATA đúng định dạng esp-csi/WiFall với 2 sóng mang con. */
    private static String row(long localTs, int rssi, int firstWordInvalid, String data) {
        return "CSI_DATA,1,2023-11-12 21:21:03.546,1,fall,d4:da:21:98:07:d9," + rssi
                + ",11,0,0,0,0,0,0,0,0,0,-99,0,12,2," + localTs + ",0,37,0,4," + firstWordInvalid + ",\"" + data + "\"";
    }

    private static Path file(Path dir, String sub, String name, String... rows) throws Exception {
        Path d = Files.createDirectories(dir.resolve(sub));
        Path f = d.resolve(name);
        // WiFall kết thúc dòng bằng \r\r\n: dòng trống phải được bỏ qua
        Files.writeString(f, HEADER + "\r\r\n" + String.join("\r\r\n", rows) + "\r\r\n", StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void ghepNhieuDoanThanhLuongLienTucCoDanhDau() throws Exception {
        Path dir = Files.createTempDirectory("espcsi");
        Path a = file(dir, "fall", "a.csv", row(1000, -30, 0, "[1, 2, 3, 4]"), row(1012, -31, 0, "[5, 6, 7, 8]"));
        Path b = file(dir, "walk", "b.csv", row(900000, -40, 0, "[-1, -2, 300, -300]"));

        StringWriter text = new StringWriter();
        try (BufferedWriter w = new BufferedWriter(text)) {
            EspCsiConvert.Stats st = EspCsiConvert.convert(List.of(a, b), w, 3);
            assertEquals(new EspCsiConvert.Stats(2, 3, 0), st);
        }
        List<String> lines = text.toString().lines().toList();
        assertTrue(lines.get(0).startsWith("# MARK 1 fall seq=0 tsUs=0"));
        assertEquals("sensorId,seq,timestampUs,rssi,imag0,real0,imag1,real1", lines.get(1));
        assertEquals("3,0,0,-30,1,2,3,4", lines.get(2));
        assertEquals("3,1,12,-31,5,6,7,8", lines.get(3));                 // giữ khoảng cách thời gian gốc
        long next = 12 + EspCsiConvert.GAP_US;
        assertTrue(lines.get(4).startsWith("# MARK 2 walk seq=2 tsUs=" + next));
        assertEquals("3,2," + next + ",-40,-1,-2,127,-128", lines.get(5)); // nối tiếp, kẹp về byte có dấu
    }

    @Test
    void boDemMicroGiayCuaEsp32TranVanTangDan(@TempDir Path dir) throws Exception {
        long max = (1L << 32) - 10;
        Path f = file(dir, "sit", "c.csv", row(max, -30, 0, "[1, 1, 1, 1]"), row(20, -30, 0, "[1, 1, 1, 1]"));
        StringWriter text = new StringWriter();
        try (BufferedWriter w = new BufferedWriter(text)) {
            EspCsiConvert.convert(List.of(f), w, 1);
        }
        List<String> data = text.toString().lines().filter(l -> !l.startsWith("#") && !l.startsWith("sensorId")).toList();
        assertEquals("1,1,30,-30,1,1,1,1", data.get(1));                   // tràn từ 2^32-10 lên 20: tăng 30 µs
    }

    @Test
    void firstWordInvalidVaDongHong(@TempDir Path dir) throws Exception {
        Path f = file(dir, "fall", "d.csv", row(0, -30, 1, "[9, 9, 9, 9, 5, 6]"), "CSI_DATA,khong,du,cot", row(10, -30, 0, "[1, 2, 3]"));
        StringWriter text = new StringWriter();
        try (BufferedWriter w = new BufferedWriter(text)) {
            EspCsiConvert.Stats st = EspCsiConvert.convert(List.of(f), w, 1);
            assertEquals(2, st.skipped());                                   // dòng thiếu cột và số lẻ phần tử
        }
        String first = text.toString().lines().filter(l -> l.startsWith("1,0,")).findFirst().orElseThrow();
        assertArrayEquals(new String[]{"1", "0", "0", "-30", "0", "0", "0", "0", "5", "6"}, first.split(","));
    }
}
