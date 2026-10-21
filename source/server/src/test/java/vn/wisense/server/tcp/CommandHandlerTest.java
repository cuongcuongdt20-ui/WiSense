package vn.wisense.server.tcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.protocol.FrameCodec;
import vn.wisense.common.protocol.Msg;
import vn.wisense.server.sensor.CsiIngest;
import vn.wisense.server.sensor.SensorRegistry;
import vn.wisense.server.udp.CsiPacketDecoder;

/**
 * Chạy TcpServer thật trên một cổng trống, client là socket thật: kiểm tra trọn đường đi
 * ClientSession → CommandHandler → SensorRegistry → CsiIngest cho cả client giám sát lẫn client cảm biến.
 */
@Timeout(15)
class CommandHandlerTest {
    private TcpServer server;
    private SensorRegistry registry;
    private CsiIngest ingest;
    private int port;
    private final List<Conn> conns = new ArrayList<>();
    private final List<CsiFrame> frames = new CopyOnWriteArrayList<>();
    private final List<Integer> configuredSensors = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> configuredKey = new AtomicReference<>();
    private final AtomicReference<Double> configuredValue = new AtomicReference<>();

    /** Một client TCP: đọc khung JSON, bỏ qua PING. */
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

        void sendBytes(byte[] packet) throws IOException {
            FrameCodec.writeFrame(out, packet);
        }

        JsonObject next() throws IOException {
            while (true) {
                JsonObject o = Msg.parse(FrameCodec.readFrame(in));
                if (!Msg.PING.equals(Msg.type(o))) return o;
            }
        }

        /** Đọc tới khi gặp tin loại type (bỏ qua tin khác, ví dụ SENSOR_STATUS chen giữa). */
        JsonObject expect(String type) throws IOException {
            while (true) {
                JsonObject o = next();
                if (type.equals(Msg.type(o))) return o;
            }
        }

        JsonObject request(String json) throws IOException {
            send(json);
            return next();
        }

        @Override public void close() throws IOException {
            socket.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        server = new TcpServer(port);
        registry = new SensorRegistry(json -> server.broadcastToMonitors(json));
        ingest = new CsiIngest((frame, stats) -> frames.add(frame), registry::touch, null);
        server.setSensorSupport(ingest, registry);
        server.setCommandHandler(new CommandHandler(
                registry::known,
                (sensor, limit) -> List.of("{\"t\":\"MOTION\",\"sensor\":" + sensor + "}"),
                (sensor, key, value) -> {
                    configuredSensors.add(sensor);
                    configuredKey.set(key);
                    configuredValue.set(value);
                },
                null,
                registry));
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

    private Conn monitor() throws IOException {
        Conn c = connect();
        assertEquals(Msg.WELCOME, Msg.type(c.request(Msg.hello("test-client"))));
        return c;
    }

    private Conn sensor(int id) throws IOException {
        Conn c = connect();
        assertEquals(Msg.WELCOME, Msg.type(c.request(Msg.helloSensor(id))));
        return c;
    }

    private static void assertErrorCode(JsonObject response, String expectedCode) {
        assertEquals(Msg.ERROR, Msg.type(response));
        assertEquals(expectedCode, response.get("code").getAsString());
    }

    private static byte[] csi(int sensor, long seq) {
        return CsiPacketDecoder.encodeForTesting(sensor, seq, 1000 * seq, -40, new byte[]{3, 4, -5, 12});
    }

    // ------------------------------------------------------------ client giám sát

    @Test
    void helloReturnsWelcomeWithSensorsAndOnline() throws Exception {
        JsonObject response = connect().request(Msg.hello("test-client"));
        assertEquals(Msg.WELCOME, Msg.type(response));
        assertTrue(response.has("session"));
        assertTrue(response.getAsJsonArray("sensors").isEmpty());
        assertTrue(response.getAsJsonArray("online").isEmpty());
    }

    @Test
    void subscribeReturnsOk() throws Exception {
        JsonObject response = monitor().request(Msg.subscribe(List.of(1, 2), true));
        assertEquals(Msg.OK, Msg.type(response));
        assertEquals(Msg.SUBSCRIBE, response.get("re").getAsString());
    }

    @Test
    void getHistoryReturnsHistoryAndEchoesReq() throws Exception {
        JsonObject response = monitor().request("{\"t\":\"GET_HISTORY\",\"req\":17,\"sensor\":1,\"limit\":10}");
        assertEquals(Msg.HISTORY, Msg.type(response));
        assertEquals(1, response.get("sensor").getAsInt());
        assertEquals(1, response.getAsJsonArray("events").size());
        assertEquals(17, response.get("req").getAsLong());
    }

    @Test
    void setConfigInvokesConfigurerAndReturnsOk() throws Exception {
        JsonObject response = monitor().request(Msg.setConfig(2, "motionThr", 0.4));
        assertEquals(Msg.OK, Msg.type(response));
        assertEquals(Msg.SET_CONFIG, response.get("re").getAsString());
        assertEquals(List.of(2), configuredSensors);
        assertEquals("motionThr", configuredKey.get());
        assertEquals(0.4, configuredValue.get());
    }

    @Test
    void ackAndPongHaveNoReplyAndKeepConnection() throws Exception {
        Conn c = monitor();
        c.send(Msg.ack(5));                                  // ACK chỉ cần đã HELLO, không trả lời
        c.send(Msg.pong());
        assertEquals(Msg.HISTORY, Msg.type(c.request(Msg.getHistory(1, 1))));
    }

    @Test
    void byeClosesConnection() throws Exception {
        Conn c = monitor();
        c.send(Msg.bye());
        assertThrows(IOException.class, c::next);           // server đóng: EOF hoặc reset
    }

    @Test
    void malformedJsonReturnsBadJsonAndKeepsConnection() throws Exception {
        Conn c = monitor();
        assertErrorCode(c.request("{not-json"), Msg.BAD_JSON);
        assertEquals(Msg.HISTORY, Msg.type(c.request(Msg.getHistory(1, 1))));
    }

    @Test
    void unknownCommandReturnsUnknownType() throws Exception {
        assertErrorCode(monitor().request("{\"t\":\"NO_SUCH_COMMAND\"}"), Msg.UNKNOWN_TYPE);
    }

    @Test
    void commandBeforeHelloReturnsInvalidState() throws Exception {
        assertErrorCode(connect().request(Msg.subscribe(List.of(1), false)), Msg.INVALID_STATE);
    }

    @Test
    void invalidArgumentReturnsBadArgWithReq() throws Exception {
        JsonObject r = monitor().request("{\"t\":\"GET_HISTORY\",\"req\":3,\"sensor\":1,\"limit\":501}");
        assertErrorCode(r, Msg.BAD_ARG);
        assertEquals(3, r.get("req").getAsLong());
    }

    @Test
    void helloTwiceReturnsInvalidState() throws Exception {
        assertErrorCode(monitor().request(Msg.hello("second-hello")), Msg.INVALID_STATE);
    }

    @Test
    void monitorCannotSendBinaryFrames() throws Exception {
        Conn c = monitor();
        c.sendBytes(csi(1, 0));
        assertErrorCode(c.next(), Msg.BAD_JSON);
        assertTrue(frames.isEmpty());
    }

    // ------------------------------------------------------------ client cảm biến

    @Test
    void sensorHelloMakesSensorOnlineForMonitors() throws Exception {
        Conn m = monitor();
        sensor(1);
        JsonObject status = m.expect(Msg.SENSOR_STATUS);
        assertEquals(1, status.get("sensor").getAsInt());
        assertTrue(status.get("online").getAsBoolean());

        JsonObject welcome = connect().request(Msg.hello("late-client"));
        assertEquals(List.of(1), List.of(welcome.getAsJsonArray("online").get(0).getAsInt()));
    }

    @Test
    void duplicateSensorIdIsRejectedAndClosed() throws Exception {
        sensor(1);
        Conn second = connect();
        assertErrorCode(second.request(Msg.helloSensor(1)), Msg.DUPLICATE_SENSOR);
        assertThrows(IOException.class, second::next);
    }

    @Test
    void sensorFramesReachIngestOnlyForOwnId() throws Exception {
        Conn s = sensor(1);
        s.sendBytes(csi(1, 0));
        s.sendBytes(csi(2, 1));                              // gói mang mã cảm biến khác: bị bỏ
        s.sendBytes(csi(1, 1));
        s.send(Msg.pong());
        for (int i = 0; i < 50 && frames.size() < 2; i++) Thread.sleep(20);
        assertEquals(2, frames.size());
        assertTrue(frames.stream().allMatch(f -> f.sensorId() == 1));
        assertEquals(1, ingest.malformedPackets());
    }

    @Test
    void sensorCannotSendMonitorCommands() throws Exception {
        assertErrorCode(sensor(1).request(Msg.subscribe(List.of(1), true)), Msg.INVALID_STATE);
    }

    @Test
    void setSensorIsRoutedToSensorAndAnswerGoesBackWithReq() throws Exception {
        Conn m = monitor();
        Conn s = sensor(1);
        m.send(Msg.setSensor(5, 1, 50));

        JsonObject config = s.expect(Msg.CONFIG);           // server → cảm biến
        assertEquals(50, config.get("pingHz").getAsInt());
        s.send(Msg.configOk(config.get("req").getAsLong(), 50));

        JsonObject ok = m.expect(Msg.OK);                    // cảm biến → server → đúng người giám sát
        assertEquals(Msg.SET_SENSOR, ok.get("re").getAsString());
        assertEquals(5, ok.get("req").getAsLong());
        assertEquals(50, ok.get("pingHz").getAsInt());
    }

    @Test
    void setSensorToOfflineSensorReturnsSensorOffline() throws Exception {
        JsonObject r = monitor().request(Msg.setSensor(9, 3, 50));
        assertErrorCode(r, Msg.SENSOR_OFFLINE);
        assertEquals(9, r.get("req").getAsLong());
    }

    @Test
    void setSensorValidatesPingHz() throws Exception {
        sensor(1);
        assertErrorCode(monitor().request(Msg.setSensor(1, 1, 0)), Msg.BAD_ARG);
    }

    @Test
    void sensorDisconnectMakesItOfflineImmediately() throws Exception {
        Conn m = monitor();
        Conn s = sensor(1);
        assertTrue(m.expect(Msg.SENSOR_STATUS).get("online").getAsBoolean());
        s.close();
        JsonObject offline = m.expect(Msg.SENSOR_STATUS);
        assertEquals(1, offline.get("sensor").getAsInt());
        assertTrue(!offline.get("online").getAsBoolean());

        sensor(1);                                           // mã đã trống: cảm biến nối lại được
        assertTrue(m.expect(Msg.SENSOR_STATUS).get("online").getAsBoolean());
    }
}
