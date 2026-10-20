package vn.wisense.server.tcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import vn.wisense.common.protocol.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Validates and handles client-to-server commands without killing a valid TCP session on bad JSON. */
public final class CommandHandler {
    @FunctionalInterface public interface HistoryProvider { List<String> recent(int sensor, int limit); }
    @FunctionalInterface public interface Configurer { void configure(int sensor, String key, double value); }

    private final Supplier<List<Integer>> knownSensors;
    private final HistoryProvider history;
    private final Configurer configurer;
    private final FallDelivery fallDelivery;

    public CommandHandler(Supplier<List<Integer>> knownSensors, HistoryProvider history,
                          Configurer configurer, FallDelivery fallDelivery) {
        this.knownSensors = knownSensors == null ? List::of : knownSensors;
        this.history = history == null ? (sensor, limit) -> List.of() : history;
        this.configurer = configurer == null ? (sensor, key, value) -> {
            throw new IllegalArgumentException("SET_CONFIG is not available yet");
        } : configurer;
        this.fallDelivery = fallDelivery;
    }

    public void handle(ClientSession session, String json) {
        JsonObject message = Msg.parse(json);
        if (message == null) {
            session.send(Msg.error(Msg.BAD_JSON, "Expected a JSON object with string field 't'"));
            return;
        }
        String type = Msg.type(message);
        try {
            switch (type) {
                case Msg.HELLO -> hello(session, message);
                case Msg.SUBSCRIBE -> subscribe(session, message);
                case Msg.GET_HISTORY -> getHistory(session, message);
                case Msg.SET_CONFIG -> setConfig(session, message);
                case Msg.ACK -> ack(session, message);
                case Msg.PONG -> { /* receiving any valid frame refreshes socket read timeout */ }
                case Msg.BYE -> session.close();
                default -> session.send(Msg.error(Msg.UNKNOWN_TYPE, "Unknown message type: " + type));
            }
        } catch (SessionStateException e) {
            session.send(Msg.error(Msg.INVALID_STATE, safeMessage(e)));
        } catch (IllegalArgumentException e) {
            session.send(Msg.error(Msg.BAD_ARG, safeMessage(e)));
        } catch (RuntimeException e) {
            // Keep the connection alive for command-level failures; log details server-side.
            System.err.println("Command " + type + " failed: " + e.getMessage());
            session.send(Msg.error(Msg.BAD_ARG, "Command could not be completed"));
        }
    }

    private void hello(ClientSession s, JsonObject m) {
        if (s.state() != ClientSession.State.CONNECTED)
            throw stateError("HELLO may only be sent once per connection");
        int version = integer(m, "ver");
        if (version != Msg.VERSION) throw new IllegalArgumentException("Unsupported protocol version: " + version);
        String name = string(m, "name").trim();
        if (name.isEmpty() || name.length() > 64) throw new IllegalArgumentException("name must contain 1..64 characters");
        s.authenticate(name);
        s.send(Msg.welcome(s.sessionId(), knownSensors.get()));
        if (fallDelivery != null) fallDelivery.onHello(s);
    }

    private void subscribe(ClientSession s, JsonObject m) {
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
        s.send(Msg.ok(Msg.SUBSCRIBE));
    }

    private void getHistory(ClientSession s, JsonObject m) {
        requireAuthed(s);
        int sensor = integer(m, "sensor");
        int limit = integer(m, "limit");
        if (sensor < 0 || sensor > 255) throw new IllegalArgumentException("sensor must be in range 0..255");
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be in range 1..500");
        List<String> events = history.recent(sensor, limit);
        s.send(Msg.history(sensor, events == null ? List.of() : events));
    }

    private void setConfig(ClientSession s, JsonObject m) {
        requireAuthed(s);
        int sensor = integer(m, "sensor");
        if (sensor < 0 || sensor > 255) throw new IllegalArgumentException("sensor must be in range 0..255");
        String key = string(m, "key").trim();
        if (key.isEmpty() || key.length() > 64) throw new IllegalArgumentException("key must contain 1..64 characters");
        JsonElement valueElement = m.get("value");
        if (valueElement == null || !valueElement.isJsonPrimitive() || !valueElement.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("value must be numeric");
        double value = valueElement.getAsDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException("value must be finite");
        configurer.configure(sensor, key, value);
        s.send(Msg.ok(Msg.SET_CONFIG));
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
    private static void requireSubscribed(ClientSession s) {
        if (s.state() != ClientSession.State.SUBSCRIBED)
            throw stateError("SUBSCRIBE is required before this command");
    }
    private static IllegalArgumentException stateError(String message) {
        return new SessionStateException(message);
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
