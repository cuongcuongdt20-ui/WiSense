package vn.wisense.server.tcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import vn.wisense.common.protocol.Msg;
import vn.wisense.server.sensor.SensorRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Kiểm tra và xử lí lệnh từ client mà không làm chết kết nối khi gặp tin sai.
 * HELLO quyết định vai trò: client giám sát (mặc định) hay client cảm biến (role=sensor).
 * Lệnh có "req" thì mọi câu trả lời, kể cả ERROR, mang lại đúng "req" đó.
 */
public final class CommandHandler {
    @FunctionalInterface public interface HistoryProvider { List<String> recent(int sensor, int limit); }
    @FunctionalInterface public interface Configurer { void configure(int sensor, String key, double value); }

    public static final int MIN_PING_HZ = 1;
    public static final int MAX_PING_HZ = 200;

    private final Supplier<List<Integer>> knownSensors;
    private final HistoryProvider history;
    private final Configurer configurer;
    private final FallDelivery fallDelivery;
    private final SensorRegistry registry;

    public CommandHandler(Supplier<List<Integer>> knownSensors, HistoryProvider history,
                          Configurer configurer, FallDelivery fallDelivery) {
        this(knownSensors, history, configurer, fallDelivery, null);
    }

    /** registry = null: server không nhận client cảm biến TCP (HELLO role=sensor và SET_SENSOR bị từ chối). */
    public CommandHandler(Supplier<List<Integer>> knownSensors, HistoryProvider history,
                          Configurer configurer, FallDelivery fallDelivery, SensorRegistry registry) {
        this.knownSensors = knownSensors == null ? List::of : knownSensors;
        this.history = history == null ? (sensor, limit) -> List.of() : history;
        this.configurer = configurer == null ? (sensor, key, value) -> {
            throw new IllegalArgumentException("SET_CONFIG is not available yet");
        } : configurer;
        this.fallDelivery = fallDelivery;
        this.registry = registry;
    }

    /** Gửi câu trả lời kèm req của lệnh đang xử lí. */
    private record Reply(ClientSession session, long req) {
        void send(String json) { session.send(Msg.withReq(json, req)); }
    }

    public void handle(ClientSession session, String json) {
        JsonObject message = Msg.parse(json);
        if (message == null) {
            session.send(Msg.error(Msg.BAD_JSON, "Expected a JSON object with string field 't'"));
            return;
        }
        String type = Msg.type(message);
        Reply r = new Reply(session, Msg.req(message));
        try {
            if (session.isSensor()) handleSensor(session, type, message, r);
            else handleMonitor(session, type, message, r);
        } catch (SessionStateException e) {
            r.send(Msg.error(Msg.INVALID_STATE, safeMessage(e)));
        } catch (IllegalArgumentException e) {
            r.send(Msg.error(Msg.BAD_ARG, safeMessage(e)));
        } catch (RuntimeException e) {
            // Keep the connection alive for command-level failures; log details server-side.
            System.err.println("Command " + type + " failed: " + e.getMessage());
            r.send(Msg.error(Msg.BAD_ARG, "Command could not be completed"));
        }
    }

    /** Client giám sát, hoặc client chưa HELLO. */
    private void handleMonitor(ClientSession s, String type, JsonObject m, Reply r) {
        switch (type) {
            case Msg.HELLO -> hello(s, m, r);
            case Msg.SUBSCRIBE -> subscribe(s, m, r);
            case Msg.GET_HISTORY -> getHistory(s, m, r);
            case Msg.SET_CONFIG -> setConfig(s, m, r);
            case Msg.SET_SENSOR -> setSensor(s, m, r);
            case Msg.ACK -> ack(s, m);
            case Msg.PONG -> { /* receiving any valid frame refreshes socket read timeout */ }
            case Msg.BYE -> s.closeAfterFlush();
            case Msg.CONFIG_OK -> throw stateError("CONFIG_OK is only sent by sensor clients");
            default -> r.send(Msg.error(Msg.UNKNOWN_TYPE, "Unknown message type: " + type));
        }
    }

    /** Client cảm biến đã HELLO: chỉ trả lời lệnh cấu hình, giữ kết nối hoặc chào tạm biệt. */
    private void handleSensor(ClientSession s, String type, JsonObject m, Reply r) {
        switch (type) {
            case Msg.CONFIG_OK -> {
                long id = longInteger(m, "req");
                int hz = integer(m, "pingHz");
                if (registry != null) registry.onConfigOk(id, hz);
            }
            case Msg.PONG -> { }
            case Msg.BYE -> s.closeAfterFlush();
            case Msg.HELLO -> throw stateError("HELLO may only be sent once per connection");
            default -> throw stateError(type + " is not allowed for sensor clients");
        }
    }

    private void hello(ClientSession s, JsonObject m, Reply r) {
        if (s.state() != ClientSession.State.CONNECTED)
            throw stateError("HELLO may only be sent once per connection");
        int version = integer(m, "ver");
        if (version != Msg.VERSION) throw new IllegalArgumentException("Unsupported protocol version: " + version);
        String role = m.has("role") ? string(m, "role") : Msg.ROLE_MONITOR;
        switch (role) {
            case Msg.ROLE_SENSOR -> helloSensor(s, m, r);
            case Msg.ROLE_MONITOR -> {
                String name = string(m, "name").trim();
                if (name.isEmpty() || name.length() > 64) throw new IllegalArgumentException("name must contain 1..64 characters");
                s.authenticate(name);
                r.send(welcome(s));
                if (fallDelivery != null) fallDelivery.onHello(s);
            }
            default -> throw new IllegalArgumentException("role must be \"monitor\" or \"sensor\"");
        }
    }

    private void helloSensor(ClientSession s, JsonObject m, Reply r) {
        if (registry == null) throw new IllegalArgumentException("This server does not accept TCP sensor clients");
        int sensor = sensorId(m, "sensor");
        if (!registry.register(sensor, s)) {
            r.send(Msg.error(Msg.DUPLICATE_SENSOR, "Sensor " + sensor + " is already connected"));
            s.closeAfterFlush();
            return;
        }
        s.authenticateSensor(sensor);
        r.send(welcome(s));
    }

    private String welcome(ClientSession s) {
        List<Integer> online = registry == null ? List.of() : registry.online();
        return Msg.welcome(s.sessionId(), knownSensors.get(), online);
    }

    private void subscribe(ClientSession s, JsonObject m, Reply r) {
        requireAuthed(s);
        JsonElement sensorsElement = m.get("sensors");
        if (sensorsElement == null || !sensorsElement.isJsonArray())
            throw new IllegalArgumentException("sensors must be an array of integer sensor IDs");
        JsonArray array = sensorsElement.getAsJsonArray();
        List<Integer> ids = new ArrayList<>();
        for (JsonElement e : array) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("Each sensor ID must be an integer");
            double d = e.getAsDouble();
            if (!Double.isFinite(d) || d != Math.rint(d) || d < 0 || d > 255)
                throw new IllegalArgumentException("Sensor IDs must be integers in range 0..255");
            int id = (int) d;
            if (!ids.contains(id)) ids.add(id);
        }
        boolean signal = false;
        if (m.has("signal")) {
            if (!m.get("signal").isJsonPrimitive() || !m.getAsJsonPrimitive("signal").isBoolean())
                throw new IllegalArgumentException("signal must be boolean");
            signal = m.get("signal").getAsBoolean();
        }
        s.subscribe(ids, signal);
        r.send(Msg.ok(Msg.SUBSCRIBE));
    }

    private void getHistory(ClientSession s, JsonObject m, Reply r) {
        requireAuthed(s);
        int sensor = sensorId(m, "sensor");
        int limit = integer(m, "limit");
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be in range 1..500");
        List<String> events = history.recent(sensor, limit);
        r.send(Msg.history(sensor, events == null ? List.of() : events));
    }

    private void setConfig(ClientSession s, JsonObject m, Reply r) {
        requireAuthed(s);
        int sensor = sensorId(m, "sensor");
        String key = string(m, "key").trim();
        if (key.isEmpty() || key.length() > 64) throw new IllegalArgumentException("key must contain 1..64 characters");
        JsonElement valueElement = m.get("value");
        if (valueElement == null || !valueElement.isJsonPrimitive() || !valueElement.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("value must be numeric");
        double value = valueElement.getAsDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException("value must be finite");
        configurer.configure(sensor, key, value);
        r.send(Msg.ok(Msg.SET_CONFIG));
    }

    /** Người giám sát đổi tần số lấy mẫu: server chuyển xuống cảm biến, OK/TIMEOUT trả về sau qua registry. */
    private void setSensor(ClientSession s, JsonObject m, Reply r) {
        requireAuthed(s);
        if (registry == null) throw new IllegalArgumentException("This server does not accept TCP sensor clients");
        int sensor = sensorId(m, "sensor");
        int hz = integer(m, "pingHz");
        if (hz < MIN_PING_HZ || hz > MAX_PING_HZ)
            throw new IllegalArgumentException("pingHz must be in range " + MIN_PING_HZ + ".." + MAX_PING_HZ);
        if (!registry.routeConfig(sensor, hz, r.req(), s::send))
            r.send(Msg.error(Msg.SENSOR_OFFLINE, "Sensor " + sensor + " is not connected over TCP"));
    }

    private void ack(ClientSession s, JsonObject m) {
        requireAuthed(s);
        long id = longInteger(m, "id");
        if (id < 0) throw new IllegalArgumentException("id must be non-negative");
        if (fallDelivery != null) fallDelivery.ack(s.name(), id);
    }

    private static void requireAuthed(ClientSession s) {
        if (s.state() != ClientSession.State.AUTHED && s.state() != ClientSession.State.SUBSCRIBED)
            throw stateError("HELLO is required before this command");
    }
    private static IllegalArgumentException stateError(String message) {
        return new SessionStateException(message);
    }
    private static int sensorId(JsonObject o, String key) {
        int id = integer(o, key);
        if (id < 0 || id > 255) throw new IllegalArgumentException(key + " must be in range 0..255");
        return id;
    }
    private static int integer(JsonObject o, String key) {
        long n = longInteger(o, key);
        if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) throw new IllegalArgumentException(key + " is out of range");
        return (int) n;
    }
    private static long longInteger(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(key + " must be an integer");
        double d = e.getAsDouble();
        if (!Double.isFinite(d) || d != Math.rint(d) || d < Long.MIN_VALUE || d > Long.MAX_VALUE)
            throw new IllegalArgumentException(key + " must be an integer");
        return e.getAsLong();
    }
    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(key + " must be a string");
        return e.getAsString();
    }
    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "Invalid command arguments" : message;
    }

    /** Special marker so the dispatcher maps state errors to INVALID_STATE, not BAD_ARG. */
    private static final class SessionStateException extends IllegalArgumentException {
        SessionStateException(String message) { super(message); }
    }
}
