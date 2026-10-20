package vn.wisense.server.tcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.wisense.common.protocol.Msg;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Integration-style tests for command validation and response JSON. */
class CommandHandlerTest {
    private ServerSocket listener;
    private Socket clientSocket;
    private ClientSession session;
    private CommandHandler handler;
    private final List<Integer> configuredSensors = new ArrayList<>();
    private final AtomicReference<String> configuredKey = new AtomicReference<>();
    private final AtomicReference<Double> configuredValue = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        listener = new ServerSocket(0);
        clientSocket = new Socket("127.0.0.1", listener.getLocalPort());
        Socket serverSocket = listener.accept();
        session = new ClientSession(serverSocket, ignored -> { });
        handler = new CommandHandler(
                () -> List.of(1, 2),
                (sensor, limit) -> List.of("{\"t\":\"MOTION\",\"sensor\":" + sensor + "}"),
                (sensor, key, value) -> {
                    configuredSensors.add(sensor);
                    configuredKey.set(key);
                    configuredValue.set(value);
                },
                null);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (session != null) session.close();
        if (clientSocket != null) clientSocket.close();
        if (listener != null) listener.close();
    }

    private JsonObject sendAndRead(String command) throws Exception {
        handler.handle(session, command);
        clientSocket.setSoTimeout(1500);
        DataInputStream in = new DataInputStream(clientSocket.getInputStream());
        int length = in.readInt();
        byte[] payload = new byte[length];
        in.readFully(payload);
        return JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private void hello() throws Exception {
        JsonObject response = sendAndRead(Msg.hello("test-client"));
        assertEquals(Msg.WELCOME, response.get("t").getAsString());
    }

    @Test
    void helloReturnsWelcomeAndAuthenticatesSession() throws Exception {
        JsonObject response = sendAndRead(Msg.hello("test-client"));
        assertEquals(Msg.WELCOME, response.get("t").getAsString());
        assertTrue(response.has("session"));
        assertTrue(response.has("sensors"));
    }

    @Test
    void subscribeReturnsOk() throws Exception {
        hello();
        JsonObject response = sendAndRead(Msg.subscribe(List.of(1, 2), true));
        assertEquals(Msg.OK, response.get("t").getAsString());
        assertEquals(Msg.SUBSCRIBE, response.get("re").getAsString());
    }

    @Test
    void getHistoryReturnsHistory() throws Exception {
        hello();
        JsonObject response = sendAndRead(Msg.getHistory(1, 10));
        assertEquals(Msg.HISTORY, response.get("t").getAsString());
        assertEquals(1, response.get("sensor").getAsInt());
        assertEquals(1, response.getAsJsonArray("events").size());
    }

    @Test
    void setConfigInvokesConfigurerAndReturnsOk() throws Exception {
        hello();
        JsonObject response = sendAndRead(Msg.setConfig(2, "motionThr", 0.4));
        assertEquals(Msg.OK, response.get("t").getAsString());
        assertEquals(Msg.SET_CONFIG, response.get("re").getAsString());
        assertEquals(List.of(2), configuredSensors);
        assertEquals("motionThr", configuredKey.get());
        assertEquals(0.4, configuredValue.get());
    }

    @Test
    void ackIsAcceptedAfterHelloEvenBeforeSubscribe() throws Exception {
        hello();
        // ACK is intentionally a no-response command; a valid following command proves the session remains usable.
        handler.handle(session, Msg.ack(42));
        JsonObject response = sendAndRead(Msg.getHistory(1, 10));
        assertEquals(Msg.HISTORY, response.get("t").getAsString());
    }

    @Test
    void pongDoesNotCloseOrRespond() throws Exception {
        hello();
        handler.handle(session, Msg.pong());
        JsonObject response = sendAndRead(Msg.getHistory(1, 1));
        assertEquals(Msg.HISTORY, response.get("t").getAsString());
    }

    @Test
    void byeClosesSession() throws Exception {
        handler.handle(session, Msg.bye());
        assertEquals(ClientSession.State.CLOSED, session.state());
    }

    @Test
    void malformedJsonReturnsBadJson() throws Exception {
        JsonObject response = sendAndRead("{not-json");
        assertErrorCode(response, Msg.BAD_JSON);
    }

    @Test
    void unknownCommandReturnsUnknownType() throws Exception {
        JsonObject response = sendAndRead("{\"t\":\"NO_SUCH_COMMAND\"}");
        assertErrorCode(response, Msg.UNKNOWN_TYPE);
    }

    @Test
    void commandBeforeHelloReturnsInvalidState() throws Exception {
        JsonObject response = sendAndRead(Msg.subscribe(List.of(1), false));
        assertErrorCode(response, Msg.INVALID_STATE);
    }

    @Test
    void invalidArgumentReturnsBadArg() throws Exception {
        hello();
        JsonObject response = sendAndRead("{\"t\":\"GET_HISTORY\",\"sensor\":1,\"limit\":501}");
        assertErrorCode(response, Msg.BAD_ARG);
    }

    @Test
    void helloTwiceReturnsInvalidState() throws Exception {
        hello();
        JsonObject response = sendAndRead(Msg.hello("second-hello"));
        assertErrorCode(response, Msg.INVALID_STATE);
    }

    private static void assertErrorCode(JsonObject response, String expectedCode) {
        assertEquals(Msg.ERROR, response.get("t").getAsString());
        assertEquals(expectedCode, response.get("code").getAsString());
    }
}
