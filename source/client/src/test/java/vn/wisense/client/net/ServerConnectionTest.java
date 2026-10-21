package vn.wisense.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.wisense.common.auth.Auth;
import vn.wisense.common.auth.Credentials;
import vn.wisense.common.protocol.FrameCodec;
import vn.wisense.common.protocol.Msg;

/** Đóng vai server bằng một ServerSocket thật để kiểm tra client gửi đúng lệnh theo thứ tự. */
@Timeout(20)
class ServerConnectionTest {
    private static final String FALL_5 = "{\"t\":\"FALL\",\"sensor\":2,\"time\":5,\"confidence\":0.8,\"id\":5}";

    /** Một kết nối phía server giả: đọc lệnh client gửi lên, ghi tin xuống. */
    private record Peer(Socket socket, DataInputStream in, DataOutputStream out) {
        static Peer accept(ServerSocket ss) throws IOException {
            Socket s = ss.accept();
            s.setSoTimeout(5000);
            return new Peer(s, new DataInputStream(s.getInputStream()), new DataOutputStream(s.getOutputStream()));
        }

        JsonObject read() throws IOException {
            return Msg.parse(FrameCodec.readFrame(in));
        }

        void write(String json) throws IOException {
            FrameCodec.writeFrame(out, json);
        }

        /** PING rồi chờ PONG: mọi tin gửi trước đó đã được client xử lí xong. */
        void sync() throws IOException {
            write(Msg.ping());
            assertEquals(Msg.PONG, Msg.type(read()));
        }
    }

    private static List<Integer> ints(JsonObject o, String key) {
        List<Integer> r = new ArrayList<>();
        o.getAsJsonArray(key).forEach(e -> r.add(e.getAsInt()));
        return r;
    }

    private static <T> List<T> drain(ConcurrentLinkedQueue<Message> inbox, Class<T> type) {
        List<T> r = new ArrayList<>();
        for (Message m : inbox) if (type.isInstance(m)) r.add(type.cast(m));
        return r;
    }

    @Test
    void batTayDangKyAckVaGuiLenh() throws IOException {
        ConcurrentLinkedQueue<Message> inbox = new ConcurrentLinkedQueue<>();
        try (ServerSocket ss = new ServerSocket(0)) {
            ServerConnection conn = new ServerConnection("127.0.0.1", ss.getLocalPort(), "ui-test", inbox, ok -> { });
            conn.setSensors(List.of(2));
            conn.start();
            Peer p = Peer.accept(ss);

            JsonObject hello = p.read();
            assertEquals(Msg.HELLO, Msg.type(hello));
            assertEquals("ui-test", hello.get("name").getAsString());
            assertEquals(Msg.VERSION, hello.get("ver").getAsInt());

            p.write(Msg.welcome(7, List.of(1, 2)));
            JsonObject sub = p.read();
            assertEquals(Msg.SUBSCRIBE, Msg.type(sub));
            assertEquals(List.of(2), ints(sub, "sensors"));   // sensor đã chọn, không phải mọi sensor
            assertEquals(true, sub.get("signal").getAsBoolean());

            p.write(FALL_5);
            assertEquals(5, p.read().get("id").getAsLong());   // ACK
            p.write(FALL_5);                                     // server gửi lại vì ACK trước bị mất
            assertEquals(5, p.read().get("id").getAsLong());   // vẫn ACK
            p.sync();
            assertEquals(1, drain(inbox, Message.Fall.class).size());    // nhưng chỉ báo một lần
            assertEquals(1, drain(inbox, Message.Welcome.class).size());

            conn.request(Msg.getHistory(2, 50));
            JsonObject q = p.read();
            assertEquals(Msg.GET_HISTORY, Msg.type(q));
            assertEquals(50, q.get("limit").getAsInt());

            conn.setSensors(List.of(1));
            assertEquals(List.of(1), ints(p.read(), "sensors"));

            conn.stop();
            assertEquals(Msg.BYE, Msg.type(p.read()));
        }
    }

    @Test
    void noiLaiThiChaoLaiVaVanLocFallTrung() throws IOException {
        ConcurrentLinkedQueue<Message> inbox = new ConcurrentLinkedQueue<>();
        try (ServerSocket ss = new ServerSocket(0)) {
            ServerConnection conn = new ServerConnection("127.0.0.1", ss.getLocalPort(), "ui-test", inbox, ok -> { });
            conn.setSensors(List.of(2));
            conn.start();

            Peer first = Peer.accept(ss);
            assertEquals(Msg.HELLO, Msg.type(first.read()));
            first.write(Msg.welcome(1, List.of(2)));
            first.read();                                        // SUBSCRIBE
            first.write(FALL_5);
            first.read();                                        // ACK
            first.sync();
            first.socket().close();                              // giả rớt mạng

            Peer second = Peer.accept(ss);                       // client tự nối lại sau 2 giây
            JsonObject hello = second.read();
            assertEquals(Msg.HELLO, Msg.type(hello));
            assertEquals("ui-test", hello.get("name").getAsString());   // cùng tên để server gửi lại FALL
            second.write(Msg.welcome(2, List.of(2)));
            assertEquals(List.of(2), ints(second.read(), "sensors"));
            second.write(FALL_5);                                // server gửi lại FALL còn chờ
            assertEquals(Msg.ACK, Msg.type(second.read()));
            second.sync();

            assertEquals(1, drain(inbox, Message.Fall.class).size());
            conn.stop();
        }
    }

    @Test
    void fallKhongCoIdThiKhongAck() throws IOException {
        ConcurrentLinkedQueue<Message> inbox = new ConcurrentLinkedQueue<>();
        try (ServerSocket ss = new ServerSocket(0)) {
            ServerConnection conn = new ServerConnection("127.0.0.1", ss.getLocalPort(), "ui-test", inbox, ok -> { });
            conn.start();
            Peer p = Peer.accept(ss);
            p.read();                                            // HELLO
            p.write("{\"t\":\"FALL\",\"sensor\":1,\"time\":5,\"confidence\":0.8}");
            p.write("{\"t\":\"FALL\",\"sensor\":1,\"time\":6,\"confidence\":0.9}");
            p.sync();                                            // tin kế tiếp là PONG, tức là không có ACK
            assertEquals(2, drain(inbox, Message.Fall.class).size());
            conn.stop();
        }
    }

    @Test
    void traLoiChallengeBangProofDungRoiDangKy() throws IOException {
        ConcurrentLinkedQueue<Message> inbox = new ConcurrentLinkedQueue<>();
        byte[] salt = Auth.randomBytes(Auth.SALT_BYTES);
        byte[] key = Auth.deriveKey("admin-pass".toCharArray(), salt, 1000);
        try (ServerSocket ss = new ServerSocket(0)) {
            ServerConnection conn = new ServerConnection("127.0.0.1", ss.getLocalPort(), "ui-test",
                    Credentials.user("khanh", "admin-pass".toCharArray()), inbox, ok -> { });
            conn.start();
            Peer p = Peer.accept(ss);
            JsonObject hello = p.read();
            assertEquals("khanh", hello.get("user").getAsString());
            String nonce = Auth.newNonce();
            p.write(Msg.challenge(nonce, Auth.hex(salt), 1000));
            JsonObject auth = p.read();
            assertEquals(Msg.AUTH, Msg.type(auth));
            assertTrue(Auth.verify(key, nonce, auth.get("proof").getAsString()));
            assertFalse(auth.toString().contains("admin-pass"));   // mật khẩu không đi trên mạng
            p.write(Msg.welcome(3, List.of(1), List.of(1), "khanh", Msg.ACCESS_VIEWER));
            assertEquals(Msg.SUBSCRIBE, Msg.type(p.read()));
            p.sync();
            Message.Welcome w = drain(inbox, Message.Welcome.class).get(0);
            assertEquals("khanh", w.user());
            assertFalse(w.isAdmin());
            conn.stop();
        }
    }

    @Test
    void authFailedThiDungKhongNoiLai() throws Exception {
        ConcurrentLinkedQueue<Message> inbox = new ConcurrentLinkedQueue<>();
        try (ServerSocket ss = new ServerSocket(0)) {
            ServerConnection conn = new ServerConnection("127.0.0.1", ss.getLocalPort(), "ui-test",
                    Credentials.user("khanh", "wrong".toCharArray()), inbox, ok -> { });
            conn.start();
            Peer p = Peer.accept(ss);
            p.read();                                            // HELLO
            p.write(Msg.challenge(Auth.newNonce(), Auth.hex(Auth.randomBytes(16)), 1000));
            p.read();                                            // AUTH
            p.write(Msg.error(Msg.AUTH_FAILED, "Wrong user, password or sensor key"));
            p.socket().close();
            Thread.sleep(2500);                                  // quá thời gian chờ nối lại 2 giây
            ss.setSoTimeout(500);
            assertThrows(java.net.SocketTimeoutException.class, ss::accept);   // không nối lại
            assertEquals(Msg.AUTH_FAILED, drain(inbox, Message.ServerError.class).get(0).code());
        }
    }

    @Test
    void guiLenhKhiChuaNoiThiBaoLoiQuaHangDoi() {
        ConcurrentLinkedQueue<Message> inbox = new ConcurrentLinkedQueue<>();
        ServerConnection conn = new ServerConnection("127.0.0.1", 1, "ui-test", inbox, ok -> { });
        conn.request(Msg.getHistory(1, 50));
        Message.ServerError e = assertInstanceOf(Message.ServerError.class, inbox.poll());
        assertEquals("NOT_CONNECTED", e.code());
    }
}
