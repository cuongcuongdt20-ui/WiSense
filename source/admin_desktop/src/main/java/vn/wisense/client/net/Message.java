package vn.wisense.client.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import vn.wisense.common.protocol.Msg;

/** Tin TCP từ server; loại tin lạ trả về null để server thêm tin mới không làm sập client. */
public sealed interface Message {
    int sensor();

    record Motion(int sensor, double level, boolean moving) implements Message {}

    record Breath(int sensor, double bpm, double snr) implements Message {}

    /** id = -1 khi server chưa gửi id (bản cũ); khi đó client không ACK và không lọc trùng. */
    record Fall(int sensor, long time, double confidence, long id) implements Message {}

    record Stats(int sensor, int pps, double loss) implements Message {}

    record Signal(int sensor, float[] amp) implements Message {}

    record Ping() implements Message {
        public int sensor() {
            return 0;
        }
    }

    /**
     * Trả lời HELLO (sau AUTH nếu server bật xác thực): số phiên, các sensor được xem, các sensor đang online,
     * tài khoản đã đăng nhập (null khi server --no-auth) và quyền "admin" hoặc "viewer".
     */
    record Welcome(int session, List<Integer> sensors, List<Integer> online, String user, String access)
            implements Message {
        public Welcome(int session, List<Integer> sensors) {
            this(session, sensors, List.of());
        }

        public Welcome(int session, List<Integer> sensors, List<Integer> online) {
            this(session, sensors, online, null, Msg.ACCESS_ADMIN);
        }

        /** Được đổi cấu hình (SET_SENSOR, SET_CONFIG). */
        public boolean isAdmin() {
            return Msg.ACCESS_ADMIN.equals(access);
        }

        public int sensor() {
            return 0;
        }
    }

    /** Server yêu cầu chứng minh mật khẩu hoặc khoá: trả AUTH với proof tính từ nonce. */
    record Challenge(String nonce, String salt, int iterations) implements Message {
        public int sensor() {
            return 0;
        }
    }

    /** Cảm biến vừa online hoặc offline (server đẩy cho mọi client giám sát). */
    record SensorStatus(int sensor, boolean online) implements Message {}

    /** Lệnh re đã được server chấp nhận; req = mã yêu cầu của lệnh đó (-1 nếu lệnh không gửi req). */
    record Ok(String re, long req) implements Message {
        public Ok(String re) {
            this(re, -1);
        }

        public int sensor() {
            return 0;
        }
    }

    /** Server từ chối lệnh, hoặc client không gửi được lệnh (code NOT_CONNECTED); req như {@link Ok}. */
    record ServerError(String code, String msg, long req) implements Message {
        public ServerError(String code, String msg) {
            this(code, msg, -1);
        }

        public int sensor() {
            return 0;
        }
    }

    // ------------------------------------------------------------ trả lời lệnh quản trị (chỉ admin nhận)

    record SensorInfo(int sensor, boolean online, boolean tcp, boolean key) {}

    record Sensors(List<SensorInfo> sensors) implements Message {
        public int sensor() {
            return 0;
        }
    }

    /** Khoá mới của một cảm biến, để admin nạp vào ESP32. */
    record SensorKey(int sensor, String secret) implements Message {}

    record UserInfo(String user, String access, String sensors) {}

    record Users(List<UserInfo> users) implements Message {
        public int sensor() {
            return 0;
        }
    }

    record SessionInfo(int session, String role, String name, String user, String address, String state,
                       boolean self) {}

    record Sessions(List<SessionInfo> sessions) implements Message {
        public int sensor() {
            return 0;
        }
    }

    /** Các sự kiện gần đây của một sensor, mới nhất trước. */
    record History(int sensor, List<Message> events) implements Message {}

    static Message parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        return parse(o);
    }

    private static Message parse(JsonObject o) {
        return switch (o.get("t").getAsString()) {
            case "MOTION" -> new Motion(sensor(o), o.get("level").getAsDouble(), o.get("moving").getAsBoolean());
            case "BREATH" -> new Breath(sensor(o), o.get("bpm").getAsDouble(), o.get("snr").getAsDouble());
            case "FALL" -> new Fall(sensor(o), o.get("time").getAsLong(), o.get("confidence").getAsDouble(),
                    o.has("id") ? o.get("id").getAsLong() : -1);
            case "STATS" -> new Stats(sensor(o), o.get("pps").getAsInt(), o.get("loss").getAsDouble());
            case "SIGNAL" -> new Signal(sensor(o), floats(o.getAsJsonArray("amp")));
            case Msg.PING -> new Ping();
            case Msg.WELCOME -> new Welcome(o.get("session").getAsInt(), ints(o.getAsJsonArray("sensors")),
                    o.has("online") ? ints(o.getAsJsonArray("online")) : List.of(),
                    o.has("user") ? o.get("user").getAsString() : null,
                    o.has("access") ? o.get("access").getAsString() : Msg.ACCESS_ADMIN);
            case Msg.CHALLENGE -> new Challenge(o.get("nonce").getAsString(), o.get("salt").getAsString(),
                    o.get("iter").getAsInt());
            case Msg.SENSOR_STATUS -> new SensorStatus(sensor(o), o.get("online").getAsBoolean());
            case Msg.OK -> new Ok(o.get("re").getAsString(), Msg.req(o));
            case Msg.ERROR -> new ServerError(o.get("code").getAsString(),
                    o.has("msg") ? o.get("msg").getAsString() : "", Msg.req(o));
            case Msg.HISTORY -> new History(sensor(o), events(o.getAsJsonArray("events")));
            case Msg.SENSORS -> new Sensors(objects(o, "sensors").stream().map(x -> new SensorInfo(
                    x.get("sensor").getAsInt(), x.get("online").getAsBoolean(), x.get("tcp").getAsBoolean(),
                    x.get("key").getAsBoolean())).toList());
            case Msg.SENSOR_KEY -> new SensorKey(sensor(o), o.get("secret").getAsString());
            case Msg.USERS -> new Users(objects(o, "users").stream().map(x -> new UserInfo(
                    x.get("user").getAsString(), x.get("access").getAsString(), x.get("sensors").getAsString())).toList());
            case Msg.SESSIONS -> new Sessions(objects(o, "sessions").stream().map(x -> new SessionInfo(
                    x.get("session").getAsInt(), x.get("role").getAsString(), x.get("name").getAsString(),
                    x.get("user").getAsString(), x.get("address").getAsString(), x.get("state").getAsString(),
                    x.has("self") && x.get("self").getAsBoolean())).toList());
            default -> null;
        };
    }

    private static List<JsonObject> objects(JsonObject o, String key) {
        List<JsonObject> r = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(key)) r.add(e.getAsJsonObject());
        return r;
    }

    private static int sensor(JsonObject o) {
        return o.get("sensor").getAsInt();
    }

    private static float[] floats(JsonArray a) {
        float[] r = new float[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsFloat();
        return r;
    }

    private static List<Integer> ints(JsonArray a) {
        List<Integer> r = new ArrayList<>(a.size());
        for (JsonElement e : a) r.add(e.getAsInt());
        return List.copyOf(r);
    }

    /** Sự kiện lạ trong lịch sử bị bỏ qua như ở parse(). */
    private static List<Message> events(JsonArray a) {
        List<Message> r = new ArrayList<>(a.size());
        for (JsonElement e : a) {
            Message m = parse(e.getAsJsonObject());
            if (m != null) r.add(m);
        }
        return List.copyOf(r);
    }
}
