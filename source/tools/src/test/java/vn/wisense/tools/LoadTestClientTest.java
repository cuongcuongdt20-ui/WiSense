package vn.wisense.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.wisense.common.protocol.FrameCodec;
import vn.wisense.common.protocol.Msg;

class LoadTestClientTest {

    @Test
    void phanViNearestRank() {
        long[] a = {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
        assertEquals(50, LoadTestClient.percentile(a, 50));
        assertEquals(100, LoadTestClient.percentile(a, 95));
        assertEquals(10, LoadTestClient.percentile(a, 0));
        assertEquals(-1, LoadTestClient.percentile(new long[0], 50));
    }

    @Test
    @Timeout(10)
    void motClientBatTayDoDoTreAckVaDemFallTrung() throws Exception {
        List<String> fromClient = new CopyOnWriteArrayList<>();
        try (ServerSocket ss = new ServerSocket(0)) {
            Thread server = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    fromClient.add(FrameCodec.readFrame(in));                   // HELLO
                    FrameCodec.writeFrame(out, Msg.welcome(1, List.of(1, 2)));
                    fromClient.add(FrameCodec.readFrame(in));                   // SUBSCRIBE
                    long now = System.currentTimeMillis();
                    for (int i = 0; i < 3; i++) {
                        FrameCodec.writeFrame(out, "{\"t\":\"MOTION\",\"sensor\":1,\"level\":0.1,\"moving\":false,\"ts\":"
                                + (now - 40) + "}");
                    }
                    String fall = "{\"t\":\"FALL\",\"sensor\":1,\"time\":5,\"confidence\":0.9,\"id\":7,\"ts\":" + now + "}";
                    FrameCodec.writeFrame(out, fall);
                    FrameCodec.writeFrame(out, fall);                           // gửi lại vì chưa thấy ACK
                    FrameCodec.writeFrame(out, Msg.ping());
                    for (int i = 0; i < 3; i++) fromClient.add(FrameCodec.readFrame(in));   // ACK, ACK, PONG
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            server.start();

            LoadTestClient.Config cfg = new LoadTestClient.Config("127.0.0.1", ss.getLocalPort(), 1, 5,
                    List.of(2), false, 0, 0, true, "test", Path.of("unused.csv"));
            LoadTestClient.Result r = LoadTestClient.runClient(cfg, 3, false, new ArrayList<>());
            server.join(3000);

            JsonObject hello = Msg.parse(fromClient.get(0));
            assertEquals("load-3", hello.get("name").getAsString());
            JsonObject sub = Msg.parse(fromClient.get(1));
            assertEquals(2, sub.getAsJsonArray("sensors").get(0).getAsInt());
            assertFalse(sub.get("signal").getAsBoolean());
            assertEquals(List.of(Msg.ACK, Msg.ACK, Msg.PONG),
                    fromClient.subList(2, 5).stream().map(j -> Msg.type(Msg.parse(j))).toList());

            assertEquals(4, r.msgs());                      // 3 MOTION + 1 FALL; bản FALL trùng và PING không tính
            assertEquals(1, r.falls());
            assertEquals(1, r.dupFalls());
            assertEquals(4, r.latencies().length);
            assertTrue(r.latencies()[3] >= 40, "do tre MOTION phai >= 40 ms");
            assertTrue(r.handshakeMs() >= 0);
            assertTrue(r.disconnected());                   // server đóng trước khi hết giờ
        }
    }

    @Test
    void thamSoMacDinhVaTuyChon() {
        LoadTestClient.Config d = LoadTestClient.parse(new String[0]);
        assertEquals(10, d.clients());
        assertTrue(d.ack());
        assertTrue(d.signal());
        LoadTestClient.Config c = LoadTestClient.parse(new String[]{"--clients=50", "--sensors=1,2", "--no-ack",
                "--no-signal", "--slow-clients=1", "--slow-ms=200", "--label=x"});
        assertEquals(50, c.clients());
        assertEquals(List.of(1, 2), c.sensors());
        assertFalse(c.ack());
        assertFalse(c.signal());
        assertEquals(1, c.slowClients());
        assertEquals(200, c.slowMs());
    }
}
