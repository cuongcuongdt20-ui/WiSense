package vn.wisense.server.tcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.wisense.common.auth.Accounts;
import vn.wisense.common.auth.Auth;
import vn.wisense.common.auth.Credentials;
import vn.wisense.common.protocol.FrameCodec;
import vn.wisense.common.protocol.Msg;
import vn.wisense.server.auth.Authenticator;
import vn.wisense.server.auth.LoginLimiter;
import vn.wisense.server.sensor.CsiIngest;
import vn.wisense.server.sensor.SensorRegistry;
import vn.wisense.server.udp.CsiPacketDecoder;

/**
 * Bắt tay HELLO → CHALLENGE → AUTH và phân quyền, chạy trên TcpServer thật với socket thật.
 * Tài khoản: khanh (admin, mọi cảm biến), me (viewer, chỉ cảm biến 1). Khoá cảm biến: 1 và 2.
 */
@Timeout(15)
class AuthHandshakeTest {
    private static final int ITER = 1000;                   // nhỏ cho test nhanh; thật là Auth.DEFAULT_ITERATIONS
    private TcpServer server;
    private SensorRegistry registry;
    private int port;
    private final List<Conn> conns = new ArrayList<>();

    private static final class Conn implements AutoCloseable {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;

        Conn(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(3000);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
        }

        void send(String json) throws IOException {
            FrameCodec.writeFrame(out, json);
        }

        JsonObject next() throws IOException {
            while (true) {
                JsonObject o = Msg.parse(FrameCodec.readFrame(in));
                if (!Msg.PING.equals(Msg.type(o))) return o;
            }
        }

        JsonObject request(String json) throws IOException {
            send(json);
            return next();
        }

        /** Gửi lệnh rồi đọc tới câu trả lời đầu tiên không phải SENSOR_STATUS (tin đẩy có thể chen giữa). */
        JsonObject command(String json) throws IOException {
            send(json);
            while (true) {
                JsonObject o = next();
                if (!Msg.SENSOR_STATUS.equals(Msg.type(o))) return o;
            }
        }

        @Override public void close() throws IOException {
            socket.close();
        }
    }

    private static Accounts.User user(String name, String access, Set<Integer> sensors, String password) {
        byte[] salt = Auth.randomBytes(Auth.SALT_BYTES);
        return new Accounts.User(name, access, sensors, ITER, salt, Auth.deriveKey(password.toCharArray(), salt, ITER));
    }

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        server = new TcpServer(port);
        registry = new SensorRegistry(json -> server.broadcastSensorStatus(json));
        CsiIngest ingest = new CsiIngest((frame, stats) -> { }, registry::touch, null);
        server.setSensorSupport(ingest, registry);
        Authenticator auth = new Authenticator(
                Map.of("khanh", user("khanh", Accounts.ADMIN, null, "admin-pass"),
                       "me", user("me", Accounts.VIEWER, Set.of(1), "viewer-pass")),
                Map.of(1, "key-one", 2, "key-two"),
                new LoginLimiter());
        server.setCommandHandler(new CommandHandler(registry::known, (s, l) -> List.of(), (s, k, v) -> { },
                null, registry, auth));
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Conn c : conns) c.close();
        server.close();
        registry.close();
    }

    private Conn connect() throws IOException {
        Conn c = new Conn(port);
        conns.add(c);
        return c;
    }

    private static String answer(JsonObject challenge, Credentials creds) {
        assertEquals(Msg.CHALLENGE, Msg.type(challenge));
        return creds.answer(challenge.get("nonce").getAsString(), challenge.get("salt").getAsString(),
                challenge.get("iter").getAsInt());
    }

    /** Đăng nhập người giám sát, trả về câu trả lời cuối (WELCOME hoặc ERROR). */
    private JsonObject login(Conn c, String user, String password) throws IOException {
        JsonObject ch = c.request(Msg.hello("ui-test", user));
        return c.request(Msg.auth(answer(ch, Credentials.user(user, password.toCharArray()))));
    }

    private Conn sensor(int id, String secret) throws IOException {
        Conn c = connect();
        JsonObject ch = c.request(Msg.helloSensor(id));
        assertEquals("", ch.get("salt").getAsString());
        assertEquals(Msg.WELCOME, Msg.type(c.request(Msg.auth(answer(ch, Credentials.sensor(secret))))));
        return c;
    }

    private static void assertError(JsonObject o, String code) {
        assertEquals(Msg.ERROR, Msg.type(o));
        assertEquals(code, o.get("code").getAsString());
    }

    private static List<Integer> ints(JsonObject o, String key) {
        List<Integer> r = new ArrayList<>();
        o.getAsJsonArray(key).forEach(e -> r.add(e.getAsInt()));
        return r;
    }

    @Test
    void dungMatKhauNhanWelcomeKemQuyen() throws Exception {
        JsonObject w = login(connect(), "khanh", "admin-pass");
        assertEquals(Msg.WELCOME, Msg.type(w));
        assertEquals("khanh", w.get("user").getAsString());
        assertEquals(Msg.ACCESS_ADMIN, w.get("access").getAsString());
    }

    @Test
    void saiMatKhauBiTuChoiVaDongKetNoi() throws Exception {
        Conn c = connect();
        assertError(login(c, "khanh", "wrong-pass"), Msg.AUTH_FAILED);
        assertThrows(IOException.class, c::next);
    }

    @Test
    void taiKhoanKhongTonTaiVanNhanChallengeRoiThatBai() throws Exception {
        Conn c = connect();
        JsonObject ch = c.request(Msg.hello("ui-test", "nobody"));
        assertEquals(Msg.CHALLENGE, Msg.type(ch));
        assertEquals(Auth.SALT_BYTES * 2, ch.get("salt").getAsString().length());   // trông như tài khoản thật
        assertError(c.request(Msg.auth(answer(ch, Credentials.user("nobody", "x".toCharArray())))), Msg.AUTH_FAILED);
    }

    @Test
    void proofCuGuiLaiBiTuChoi() throws Exception {
        Credentials creds = Credentials.user("khanh", "admin-pass".toCharArray());
        Conn first = connect();
        String oldProof = answer(first.request(Msg.hello("ui-a", "khanh")), creds);
        assertEquals(Msg.WELCOME, Msg.type(first.request(Msg.auth(oldProof))));

        Conn second = connect();
        JsonObject ch = second.request(Msg.hello("ui-b", "khanh"));
        assertEquals(Msg.CHALLENGE, Msg.type(ch));            // nonce mới
        assertError(second.request(Msg.auth(oldProof)), Msg.AUTH_FAILED);
    }

    @Test
    void lenhTruocAuthBiInvalidStateNhungVanDangNhapDuoc() throws Exception {
        Conn c = connect();
        Credentials creds = Credentials.user("khanh", "admin-pass".toCharArray());
        JsonObject ch = c.request(Msg.hello("ui-test", "khanh"));
        assertError(c.request(Msg.subscribe(List.of(1), false)), Msg.INVALID_STATE);
        assertEquals(Msg.WELCOME, Msg.type(c.request(Msg.auth(answer(ch, creds)))));
    }

    @Test
    void authKhongCoChallengeBiInvalidState() throws Exception {
        assertError(connect().request(Msg.auth("00")), Msg.INVALID_STATE);
    }

    @Test
    void helloThieuUserBiBadArg() throws Exception {
        assertError(connect().request(Msg.hello("ui-test")), Msg.BAD_ARG);
    }

    @Test
    void viewerChiThayVaChiXemCamBienDuocPhep() throws Exception {
        sensor(1, "key-one");
        sensor(2, "key-two");
        Conn c = connect();
        JsonObject w = login(c, "me", "viewer-pass");
        assertEquals(Msg.ACCESS_VIEWER, w.get("access").getAsString());
        assertEquals(List.of(1), ints(w, "sensors"));
        assertError(c.request(Msg.subscribe(List.of(1, 2), false)), Msg.FORBIDDEN);
        assertEquals(Msg.OK, Msg.type(c.request(Msg.subscribe(List.of(1), false))));
        assertError(c.request(Msg.getHistory(2, 5)), Msg.FORBIDDEN);
    }

    @Test
    void viewerKhongDuocDieuKhien() throws Exception {
        sensor(1, "key-one");
        Conn c = connect();
        login(c, "me", "viewer-pass");
        assertError(c.request(Msg.setSensor(3, 1, 50)), Msg.FORBIDDEN);
        assertError(c.request(Msg.setConfig(1, "motionThr", 0.3)), Msg.FORBIDDEN);
        assertEquals(Msg.HISTORY, Msg.type(c.request(Msg.getHistory(1, 5))));   // vẫn giữ kết nối
    }

    @Test
    void adminDieuKhienDuoc() throws Exception {
        sensor(1, "key-one");
        Conn c = connect();
        login(c, "khanh", "admin-pass");
        assertEquals(Msg.OK, Msg.type(c.request(Msg.setConfig(1, "motionThr", 0.3))));
    }

    @Test
    void camBienSaiKhoaHoacChuaCoKhoaBiTuChoi() throws Exception {
        Conn wrong = connect();
        JsonObject ch = wrong.request(Msg.helloSensor(1));
        assertError(wrong.request(Msg.auth(answer(ch, Credentials.sensor("guess")))), Msg.AUTH_FAILED);

        Conn noKey = connect();
        ch = noKey.request(Msg.helloSensor(9));
        assertError(noKey.request(Msg.auth(answer(ch, Credentials.sensor("anything")))), Msg.AUTH_FAILED);
        assertTrue(registry.known().isEmpty());
    }

    @Test
    void camBienChuaAuthKhongGuiDuocCsi() throws Exception {
        Conn c = connect();
        c.request(Msg.helloSensor(1));
        FrameCodec.writeFrame(c.out, CsiPacketDecoder.encodeForTesting(1, 1, 1000, -40, new byte[]{3, 4}));
        assertError(c.next(), Msg.BAD_JSON);
        assertFalse(registry.online().contains(1));
    }

    @Test
    void viewerKhongNhanTrangThaiCamBienKhongDuocXem() throws Exception {
        Conn viewer = connect();
        login(viewer, "me", "viewer-pass");
        Conn s2 = sensor(2, "key-two");
        Conn s1 = sensor(1, "key-one");
        FrameCodec.writeFrame(s2.out, CsiPacketDecoder.encodeForTesting(2, 1, 1000, -40, new byte[]{3, 4}));
        Thread.sleep(200);                                   // để SENSOR_STATUS của cảm biến 2 (nếu có) tới trước
        FrameCodec.writeFrame(s1.out, CsiPacketDecoder.encodeForTesting(1, 1, 1000, -40, new byte[]{3, 4}));
        JsonObject st = viewer.next();
        assertEquals(Msg.SENSOR_STATUS, Msg.type(st));
        assertEquals(1, st.get("sensor").getAsInt());        // tin đầu tiên là của cảm biến 1, không phải 2
    }

    // ------------------------------------------------------------ lệnh quản trị

    private Conn admin() throws IOException {
        Conn c = connect();
        assertEquals(Msg.WELCOME, Msg.type(login(c, "khanh", "admin-pass")));
        return c;
    }

    private static String saveUserMsg(long req, String user, String access, List<Integer> sensors, String password) {
        byte[] salt = Auth.randomBytes(Auth.SALT_BYTES);
        int iter = 10_000;
        return Msg.saveUser(req, user, access, sensors, Auth.hex(salt), iter,
                Auth.hex(Auth.deriveKey(password.toCharArray(), salt, iter)));
    }

    @Test
    void viewerKhongDungDuocLenhQuanTri() throws Exception {
        Conn c = connect();
        login(c, "me", "viewer-pass");
        assertError(c.request(Msg.request(Msg.LIST_USERS, 1)), Msg.FORBIDDEN);
        assertError(c.request(Msg.request(Msg.LIST_SESSIONS, 2)), Msg.FORBIDDEN);
        assertError(c.request(saveUserMsg(3, "me", Accounts.ADMIN, null, "hacked-pass")), Msg.FORBIDDEN);
    }

    @Test
    void adminThemTaiKhoanMoiRoiTaiKhoanDoDangNhapDuoc() throws Exception {
        Conn a = admin();
        JsonObject ok = a.request(saveUserMsg(7, "tv2", Accounts.VIEWER, List.of(1), "tv2-pass"));
        assertEquals(Msg.OK, Msg.type(ok));
        assertEquals(7, ok.get("req").getAsLong());
        JsonObject users = a.request(Msg.request(Msg.LIST_USERS, 8));
        assertEquals(Msg.USERS, Msg.type(users));
        assertEquals(3, users.getAsJsonArray("users").size());

        JsonObject w = login(connect(), "tv2", "tv2-pass");
        assertEquals(Msg.WELCOME, Msg.type(w));
        assertEquals(Msg.ACCESS_VIEWER, w.get("access").getAsString());
    }

    @Test
    void xoaTaiKhoanThiPhienCuaNoBiNgat() throws Exception {
        Conn viewer = connect();
        login(viewer, "me", "viewer-pass");
        Conn a = admin();
        assertEquals(Msg.OK, Msg.type(a.request(Msg.deleteUser(1, "me"))));
        assertError(viewer.next(), Msg.KICKED);
        assertThrows(IOException.class, viewer::next);
        assertError(login(connect(), "me", "viewer-pass"), Msg.AUTH_FAILED);
    }

    @Test
    void khongTuXoaHoacTuHaQuyenAdminCuoiCung() throws Exception {
        Conn a = admin();
        assertError(a.request(Msg.deleteUser(1, "khanh")), Msg.BAD_ARG);
        assertError(a.request(saveUserMsg(2, "khanh", Accounts.VIEWER, null, "admin-pass")), Msg.BAD_ARG);
        assertError(a.request(saveUserMsg(3, "bad,name", Accounts.VIEWER, null, "x-pass1")), Msg.BAD_ARG);
    }

    @Test
    void taoKhoaCamBienMoiThiCamBienCuBiNgatVaKhoaMoiDungDuoc() throws Exception {
        Conn oldSensor = sensor(1, "key-one");
        Conn a = admin();
        JsonObject key = a.command(Msg.request(Msg.NEW_SENSOR_KEY, 4, "sensor", 1));
        assertEquals(Msg.SENSOR_KEY, Msg.type(key));
        String secret = key.get("secret").getAsString();
        assertError(oldSensor.next(), Msg.KICKED);
        Thread.sleep(200);                                   // để server đóng hẳn kết nối cũ
        sensor(1, secret);                                   // khoá mới đăng nhập được

        JsonObject list = a.command(Msg.request(Msg.LIST_SENSORS, 5));
        assertEquals(Msg.SENSORS, Msg.type(list));
        JsonObject first = list.getAsJsonArray("sensors").get(0).getAsJsonObject();
        assertEquals(1, first.get("sensor").getAsInt());
        assertTrue(first.get("tcp").getAsBoolean());
        assertTrue(first.get("key").getAsBoolean());
    }

    @Test
    void adminXemVaNgatPhienKhac() throws Exception {
        Conn viewer = connect();
        login(viewer, "me", "viewer-pass");
        Conn a = admin();
        JsonObject sessions = a.request(Msg.request(Msg.LIST_SESSIONS, 1));
        assertEquals(Msg.SESSIONS, Msg.type(sessions));
        int viewerSession = -1;
        for (var e : sessions.getAsJsonArray("sessions")) {
            JsonObject o = e.getAsJsonObject();
            if ("me".equals(o.get("user").getAsString())) viewerSession = o.get("session").getAsInt();
        }
        assertTrue(viewerSession > 0);
        assertEquals(Msg.OK, Msg.type(a.request(Msg.request(Msg.KICK, 2, "session", viewerSession))));
        assertError(viewer.next(), Msg.KICKED);
    }

    @Test
    void saiNhieuLanBiChanTam() throws Exception {
        for (int i = 0; i < LoginLimiter.MAX_FAILURES; i++) {
            assertError(login(connect(), "khanh", "wrong-" + i), Msg.AUTH_FAILED);
        }
        Conn c = connect();
        assertError(c.request(Msg.hello("ui-test", "khanh")), Msg.TOO_MANY_ATTEMPTS);
        assertThrows(IOException.class, c::next);
    }
}
