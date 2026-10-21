package vn.wisense.server.tcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import vn.wisense.common.protocol.Msg;
import vn.wisense.server.auth.Access;
import vn.wisense.server.auth.Authenticator;
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
    private final Authenticator authenticator;

    public CommandHandler(Supplier<List<Integer>> knownSensors, HistoryProvider history,
                          Configurer configurer, FallDelivery fallDelivery) {
        this(knownSensors, history, configurer, fallDelivery, null);
    }

    /** registry = null: server không nhận client cảm biến TCP (HELLO role=sensor và SET_SENSOR bị từ chối). */
    public CommandHandler(Supplier<List<Integer>> knownSensors, HistoryProvider history,
                          Configurer configurer, FallDelivery fallDelivery, SensorRegistry registry) {
        this(knownSensors, history, configurer, fallDelivery, registry, null);
    }

    /** authenticator = null: không xác thực, HELLO nhận WELCOME ngay với toàn quyền (chế độ --no-auth). */
    public CommandHandler(Supplier<List<Integer>> knownSensors, HistoryProvider history,
                          Configurer configurer, FallDelivery fallDelivery, SensorRegistry registry,
                          Authenticator authenticator) {
        this.authenticator = authenticator;
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
        } catch (ForbiddenException e) {
            r.send(Msg.error(Msg.FORBIDDEN, safeMessage(e)));
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
            case Msg.AUTH -> auth(s, m, r);
            case Msg.SUBSCRIBE -> subscribe(s, m, r);
            case Msg.GET_HISTORY -> getHistory(s, m, r);
            case Msg.SET_CONFIG -> setConfig(s, m, r);
            case Msg.SET_SENSOR -> setSensor(s, m, r);
            case Msg.ACK -> ack(s, m);
            case Msg.PONG -> { /* receiving any valid frame refreshes socket read timeout */ }
            case Msg.BYE -> s.closeAfterFlush();
            case Msg.CONFIG_OK -> throw stateError("CONFIG_OK is only sent by sensor clients");
            case Msg.LIST_SENSORS -> listSensors(s, r);
            case Msg.NEW_SENSOR_KEY -> newSensorKey(s, m, r);
            case Msg.DELETE_SENSOR_KEY -> deleteSensorKey(s, m, r);
            case Msg.LIST_USERS -> listUsers(s, r);
            case Msg.SAVE_USER -> saveUser(s, m, r);
            case Msg.DELETE_USER -> deleteUser(s, m, r);
            case Msg.LIST_SESSIONS -> listSessions(s, r);
            case Msg.KICK -> kick(s, m, r);
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
            case Msg.ROLE_SENSOR -> {
                if (registry == null) throw new IllegalArgumentException("This server does not accept TCP sensor clients");
                int sensor = sensorId(m, "sensor");
                if (authenticator == null) acceptSensor(s, sensor, r);
                else sendChallenge(s, new ClientSession.PendingAuth(ClientSession.Role.SENSOR, null, sensor,
                        authenticator.forSensor(sensor)), r);
            }
            case Msg.ROLE_MONITOR -> {
                String name = string(m, "name").trim();
                if (name.isEmpty() || name.length() > 64) throw new IllegalArgumentException("name must contain 1..64 characters");
                if (authenticator == null) {
                    acceptMonitor(s, name, Access.ALL, r);
                } else {
                    if (!m.has("user")) throw new IllegalArgumentException("user is required: this server requires login");
                    String user = string(m, "user").trim();
                    if (user.isEmpty() || user.length() > 32) throw new IllegalArgumentException("user must contain 1..32 characters");
                    sendChallenge(s, new ClientSession.PendingAuth(ClientSession.Role.MONITOR, name, -1,
                            authenticator.forUser(user)), r);
                }
            }
            default -> throw new IllegalArgumentException("role must be \"monitor\" or \"sensor\"");
        }
    }

    /** IP đang bị chặn vì sai nhiều lần thì từ chối ngay; không thì gửi CHALLENGE và chờ AUTH. */
    private void sendChallenge(ClientSession s, ClientSession.PendingAuth p, Reply r) {
        if (authenticator.limiter().isBlocked(s.remoteAddress())) {
            r.send(Msg.error(Msg.TOO_MANY_ATTEMPTS, "Too many failed logins, try again later"));
            s.closeAfterFlush();
            return;
        }
        s.challenge(p);
        Authenticator.Challenge c = p.challenge();
        r.send(Msg.challenge(c.nonce(), c.salt(), c.iterations()));
    }

    /** Kiểm proof; đúng thì nhận client như HELLO không xác thực, sai thì AUTH_FAILED rồi đóng. */
    private void auth(ClientSession s, JsonObject m, Reply r) {
        if (s.state() != ClientSession.State.CHALLENGED) throw stateError("AUTH is only accepted right after CHALLENGE");
        ClientSession.PendingAuth p = s.takePending();
        String proof = string(m, "proof");
        String address = s.remoteAddress();
        if (!authenticator.verify(p.challenge(), proof)) {
            authenticator.limiter().failure(address);
            System.err.println("Dang nhap sai tu " + address + ": "
                    + (p.role() == ClientSession.Role.SENSOR ? "cam bien " + p.sensor() : "tai khoan"));
            r.send(Msg.error(Msg.AUTH_FAILED, "Wrong user, password or sensor key"));
            s.closeAfterFlush();
            return;
        }
        authenticator.limiter().success(address);
        if (p.role() == ClientSession.Role.SENSOR) acceptSensor(s, p.sensor(), r);
        else acceptMonitor(s, p.name(), p.challenge().access(), r);
    }

    private void acceptMonitor(ClientSession s, String name, Access access, Reply r) {
        s.authenticate(name, access);
        r.send(welcome(s));
        if (fallDelivery != null) fallDelivery.onHello(s);
    }

    private void acceptSensor(ClientSession s, int sensor, Reply r) {
        if (!registry.register(sensor, s)) {
            r.send(Msg.error(Msg.DUPLICATE_SENSOR, "Sensor " + sensor + " is already connected"));
            s.closeAfterFlush();
            return;
        }
        s.authenticateSensor(sensor);
        r.send(welcome(s));
    }

    /** WELCOME chỉ liệt kê cảm biến tài khoản được xem; có xác thực thì kèm tên tài khoản và quyền. */
    private String welcome(ClientSession s) {
        if (s.isSensor()) return Msg.welcome(s.sessionId(), List.of(), List.of());
        Access a = s.access();
        List<Integer> known = knownSensors.get().stream().filter(a::canSee).toList();
        List<Integer> online = registry == null ? List.of() : registry.online().stream().filter(a::canSee).toList();
        if (authenticator == null) return Msg.welcome(s.sessionId(), known, online);
        return Msg.welcome(s.sessionId(), known, online, a.user(), a.level());
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
            requireSee(s, id);
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
        requireSee(s, sensor);
        int limit = integer(m, "limit");
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be in range 1..500");
        List<String> events = history.recent(sensor, limit);
        r.send(Msg.history(sensor, events == null ? List.of() : events));
    }

    private void setConfig(ClientSession s, JsonObject m, Reply r) {
        requireAuthed(s);
        int sensor = sensorId(m, "sensor");
        requireAdmin(s, sensor);
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
        requireAdmin(s, sensor);
        int hz = integer(m, "pingHz");
        if (hz < MIN_PING_HZ || hz > MAX_PING_HZ)
            throw new IllegalArgumentException("pingHz must be in range " + MIN_PING_HZ + ".." + MAX_PING_HZ);
        if (!registry.routeConfig(sensor, hz, r.req(), s::send))
            r.send(Msg.error(Msg.SENSOR_OFFLINE, "Sensor " + sensor + " is not connected over TCP"));
    }

    // ------------------------------------------------------------ quản trị: chỉ tài khoản admin

    /** Mọi cảm biến server biết (đã từng gửi CSI hoặc đã có khoá): online, nối TCP, có khoá. */
    private void listSensors(ClientSession s, Reply r) {
        requireAdminOnly(s);
        java.util.TreeSet<Integer> ids = new java.util.TreeSet<>(knownSensors.get());
        if (authenticator != null) ids.addAll(authenticator.sensorsWithKey());
        List<Integer> online = registry == null ? List.of() : registry.online();
        JsonArray items = new JsonArray();
        for (int id : ids) {
            JsonObject o = new JsonObject();
            o.addProperty("sensor", id);
            o.addProperty("online", online.contains(id));
            o.addProperty("tcp", registry != null && registry.isTcp(id));
            o.addProperty("key", authenticator == null || authenticator.hasSensorKey(id));
            items.add(o);
        }
        r.send(Msg.list(Msg.SENSORS, "sensors", items));
    }

    /** Khoá mới thay khoá cũ: cảm biến đang nối bằng khoá cũ bị ngắt và phải đăng nhập lại. */
    private void newSensorKey(ClientSession s, JsonObject m, Reply r) {
        requireAccounts(s);
        int sensor = sensorId(m, "sensor");
        String secret = io(() -> authenticator.newSensorKey(sensor));
        kickSensor(s, sensor, "Sensor key was replaced");
        System.out.println("Admin " + s.access().user() + " tao khoa moi cho cam bien " + sensor);
        r.send(Msg.sensorKey(sensor, secret));
    }

    private void deleteSensorKey(ClientSession s, JsonObject m, Reply r) {
        requireAccounts(s);
        int sensor = sensorId(m, "sensor");
        if (!io(() -> authenticator.deleteSensorKey(sensor)))
            throw new IllegalArgumentException("Sensor " + sensor + " has no key");
        kickSensor(s, sensor, "Sensor key was deleted");
        r.send(Msg.ok(Msg.DELETE_SENSOR_KEY));
    }

    private void listUsers(ClientSession s, Reply r) {
        requireAccounts(s);
        JsonArray items = new JsonArray();
        for (vn.wisense.common.auth.Accounts.User u : authenticator.users()) {
            JsonObject o = new JsonObject();
            o.addProperty("user", u.name());
            o.addProperty("access", u.access());
            o.addProperty("sensors", vn.wisense.common.auth.Accounts.formatSensors(u.sensors()));
            items.add(o);
        }
        r.send(Msg.list(Msg.USERS, "users", items));
    }

    /**
     * Thêm hoặc sửa tài khoản. Client gửi salt và key đã tính từ mật khẩu, không gửi mật khẩu.
     * Phiên khác của tài khoản này bị ngắt để đăng nhập lại với quyền mới.
     */
    private void saveUser(ClientSession s, JsonObject m, Reply r) {
        requireAccounts(s);
        String name = string(m, "user").trim();
        String access = string(m, "access");
        java.util.Set<Integer> sensors = null;
        JsonElement se = m.get("sensors");
        if (se != null && se.isJsonArray()) {
            sensors = new java.util.TreeSet<>();
            for (JsonElement e : se.getAsJsonArray()) {
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException("sensors must be \"*\" or an array of sensor IDs");
                int id = e.getAsInt();
                if (id < 0 || id > 255) throw new IllegalArgumentException("Sensor IDs must be in range 0..255");
                sensors.add(id);
            }
        } else if (se == null || !se.isJsonPrimitive() || !"*".equals(se.getAsString())) {
            throw new IllegalArgumentException("sensors must be \"*\" or an array of sensor IDs");
        }
        int iterations = integer(m, "iter");
        if (iterations < 10_000 || iterations > 10_000_000) throw new IllegalArgumentException("iter must be 10000..10000000");
        byte[] salt;
        byte[] key;
        try {
            salt = vn.wisense.common.auth.Auth.unhex(string(m, "salt"));
            key = vn.wisense.common.auth.Auth.unhex(string(m, "key"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("salt and key must be hex strings");
        }
        if (salt.length < 8 || key.length != vn.wisense.common.auth.Auth.KEY_BYTES)
            throw new IllegalArgumentException("salt must have at least 8 bytes and key exactly 32 bytes");
        vn.wisense.common.auth.Accounts.User u =
                new vn.wisense.common.auth.Accounts.User(name, access, sensors, iterations, salt, key);
        String me = s.access().user();
        io(() -> { authenticator.saveUser(u, me); return null; });
        if (!name.equals(me)) kickUser(s, name, "Account was changed by an admin, log in again");
        System.out.println("Admin " + me + " luu tai khoan " + name + " (" + access + ")");
        r.send(Msg.ok(Msg.SAVE_USER));
    }

    private void deleteUser(ClientSession s, JsonObject m, Reply r) {
        requireAccounts(s);
        String name = string(m, "user").trim();
        String me = s.access().user();
        if (!io(() -> authenticator.deleteUser(name, me))) throw new IllegalArgumentException("No account " + name);
        kickUser(s, name, "Account was deleted");
        System.out.println("Admin " + me + " xoa tai khoan " + name);
        r.send(Msg.ok(Msg.DELETE_USER));
    }

    /** Các kết nối đang mở: phiên, vai trò, tên, tài khoản, địa chỉ, trạng thái. */
    private void listSessions(ClientSession s, Reply r) {
        requireAdminOnly(s);
        JsonArray items = new JsonArray();
        List<ClientSession> all = new ArrayList<>(s.server().sessions());
        all.sort(java.util.Comparator.comparingInt(ClientSession::sessionId));
        for (ClientSession c : all) {
            if (!c.isOpen()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("session", c.sessionId());
            o.addProperty("role", c.isSensor() ? "sensor" : c.role() == ClientSession.Role.MONITOR ? "monitor" : "-");
            o.addProperty("name", c.isSensor() ? "cam bien " + c.sensorId() : c.name() == null ? "" : c.name());
            String user = c.role() == ClientSession.Role.MONITOR ? c.access().user() : null;
            o.addProperty("user", user == null ? "" : user);
            o.addProperty("address", c.remoteAddress());
            o.addProperty("state", c.state().name());
            o.addProperty("self", c == s);
            items.add(o);
        }
        r.send(Msg.list(Msg.SESSIONS, "sessions", items));
    }

    private void kick(ClientSession s, JsonObject m, Reply r) {
        requireAdminOnly(s);
        int id = integer(m, "session");
        if (id == s.sessionId()) throw new IllegalArgumentException("Cannot disconnect your own session");
        ClientSession target = s.server().sessions().stream()
                .filter(c -> c.sessionId() == id && c.isOpen()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No open session " + id));
        target.kick("Disconnected by admin " + (s.access().user() == null ? "" : s.access().user()));
        System.out.println("Admin ngat phien " + id);
        r.send(Msg.ok(Msg.KICK));
    }

    private static void kickUser(ClientSession s, String user, String reason) {
        for (ClientSession c : s.server().sessions())
            if (c != s && c.role() == ClientSession.Role.MONITOR && user.equals(c.access().user())) c.kick(reason);
    }

    private static void kickSensor(ClientSession s, int sensor, String reason) {
        for (ClientSession c : s.server().sessions())
            if (c.isSensor() && c.sensorId() == sensor) c.kick(reason);
    }

    private static void requireAdminOnly(ClientSession s) {
        requireAuthed(s);
        if (!s.access().admin()) throw new ForbiddenException("Only admin accounts may use administration commands");
    }

    /** Quản lí tài khoản và khoá cần server bật xác thực. */
    private void requireAccounts(ClientSession s) {
        requireAdminOnly(s);
        if (authenticator == null) throw new IllegalArgumentException("Server is running with --no-auth: no accounts to manage");
    }

    @FunctionalInterface private interface IoAction<T> { T run() throws java.io.IOException; }

    /** Lỗi ghi file tài khoản: báo cho admin, không làm chết kết nối. */
    private static <T> T io(IoAction<T> action) {
        try {
            return action.run();
        } catch (java.io.IOException e) {
            System.err.println("Khong ghi duoc file tai khoan: " + e.getMessage());
            throw new IllegalArgumentException("Could not write account file: " + e.getMessage());
        }
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
    private static void requireSee(ClientSession s, int sensor) {
        if (!s.access().canSee(sensor)) throw new ForbiddenException("No access to sensor " + sensor);
    }
    /** SET_CONFIG, SET_SENSOR: chỉ tài khoản admin, và chỉ trên cảm biến được xem. */
    private static void requireAdmin(ClientSession s, int sensor) {
        if (!s.access().admin()) throw new ForbiddenException("Only admin accounts may change settings");
        requireSee(s, sensor);
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

    /** Lệnh hợp lệ nhưng tài khoản không có quyền: trả FORBIDDEN, giữ kết nối. */
    private static final class ForbiddenException extends RuntimeException {
        ForbiddenException(String message) { super(message); }
    }
}
