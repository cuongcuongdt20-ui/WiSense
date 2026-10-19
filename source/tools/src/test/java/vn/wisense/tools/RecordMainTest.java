package vn.wisense.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RecordMainTest {
    /** Dùng chính hàm của ReplayMain để tạo gói, nên test cũng kiểm tra hai công cụ khớp định dạng với nhau. */
    private static byte[] packet(String row) throws Exception {
        Method m = ReplayMain.class.getDeclaredMethod("csvRowToPacket", String[].class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, (Object) row.split(","));
    }

    @Test
    void ghiRoiPhatLaiChoGoiGiongHet() throws Exception {
        for (String row : List.of("1,2,3000,-40,3,4,-5,12", "255,4294967295,9007199254740993,127,-128,127,0,0,1,-1")) {
            byte[] p = packet(row);
            String recorded = RecordMain.toCsvRow(p, p.length);
            assertEquals(row, recorded);
            assertArrayEquals(p, packet(recorded));
        }
    }

    @Test
    void goiSaiDinhDangBiTuChoi() throws Exception {
        byte[] p = packet("1,2,3000,-40,3,4,-5,12");
        assertThrows(IllegalArgumentException.class, () -> RecordMain.toCsvRow(p, 10));        // quá ngắn
        assertThrows(IllegalArgumentException.class, () -> RecordMain.toCsvRow(p, p.length - 1)); // thiếu byte
        byte[] wrongMagic = Arrays.copyOf(p, p.length);
        wrongMagic[3] = '2';
        assertThrows(IllegalArgumentException.class, () -> RecordMain.toCsvRow(wrongMagic, wrongMagic.length));
    }

    @Test
    @Timeout(10)
    void vongGhiNhanGoiThatDemHutVaChuyenTiep() throws Exception {
        try (DatagramSocket in = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket fwd = new DatagramSocket();
             DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket esp = new DatagramSocket()) {
            server.setSoTimeout(3000);
            StringWriter text = new StringWriter();
            AtomicBoolean running = new AtomicBoolean(true);
            RecordMain.Stats[] result = new RecordMain.Stats[1];
            Thread t = new Thread(() -> {
                try (BufferedWriter w = new BufferedWriter(text)) {
                    result[0] = RecordMain.record(in, w, Long.MAX_VALUE, running, fwd,
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            t.start();

            InetSocketAddress to = new InetSocketAddress(InetAddress.getLoopbackAddress(), in.getLocalPort());
            List<byte[]> sent = List.of(packet("1,0,1000,-40,3,4"), packet("1,1,2000,-40,5,6"),
                    "rac".getBytes(), packet("1,3,4000,-41,7,8"));   // seq 2 bị hụt, một gói rác
            for (byte[] p : sent) esp.send(new DatagramPacket(p, p.length, to));

            byte[] buf = new byte[256];
            for (int i = 0; i < sent.size(); i++) {             // server nhận đủ 4 gói chuyển tiếp, kể cả gói rác
                DatagramPacket got = new DatagramPacket(buf, buf.length);
                server.receive(got);
                assertArrayEquals(sent.get(i), Arrays.copyOf(got.getData(), got.getLength()));
            }
            running.set(false);
            t.join(3000);

            assertEquals(new RecordMain.Stats(3, 1, 1), result[0]);
            assertEquals(List.of("sensorId,seq,timestampUs,rssi,imag0,real0",
                    "1,0,1000,-40,3,4", "1,1,2000,-40,5,6", "1,3,4000,-41,7,8"), text.toString().lines().toList());
        }
    }
}
