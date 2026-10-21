package vn.wisense.common.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.Collection;
import java.util.List;

/**
 * Tên loại tin, mã lỗi và các hàm tạo JSON cho giao thức TCP.
 * Mọi tin đều có trường "t" là loại tin; server và client cùng dùng để không gõ lệch tên.
 *
 * <p>Một server, hai loại client cùng nối vào một cổng và khai báo vai trò trong HELLO:
 * client giám sát (role "monitor", mặc định) và client cảm biến (role "sensor", gửi gói CSI1 nhị phân).
 * Lệnh có thể kèm "req"; câu trả lời mang lại đúng "req" đó để client ghép yêu cầu với câu trả lời.
 *
 * <p>Khi server bật xác thực, HELLO được trả bằng CHALLENGE; client gửi AUTH với proof (xem {@code Auth})
 * rồi mới nhận WELCOME. Sai thì ERROR AUTH_FAILED và server đóng kết nối.
 */
public final class Msg {
    public static final int VERSION = 1;

    // vai trò trong HELLO
    public static final String ROLE_MONITOR = "monitor";
    public static final String ROLE_SENSOR = "sensor";

    // quyền của tài khoản, gửi trong WELCOME
    public static final String ACCESS_ADMIN = "admin";
    public static final String ACCESS_VIEWER = "viewer";

    // client giám sát → server
    public static final String HELLO = "HELLO";
    public static final String AUTH = "AUTH";
    public static final String SUBSCRIBE = "SUBSCRIBE";
    public static final String GET_HISTORY = "GET_HISTORY";
    public static final String SET_CONFIG = "SET_CONFIG";
    public static final String SET_SENSOR = "SET_SENSOR";
    public static final String ACK = "ACK";
    public static final String PONG = "PONG";
    public static final String BYE = "BYE";

    // client giám sát (admin) → server: quản trị
    public static final String LIST_SENSORS = "LIST_SENSORS";
    public static final String NEW_SENSOR_KEY = "NEW_SENSOR_KEY";
    public static final String DELETE_SENSOR_KEY = "DELETE_SENSOR_KEY";
    public static final String LIST_USERS = "LIST_USERS";
    public static final String SAVE_USER = "SAVE_USER";
    public static final String DELETE_USER = "DELETE_USER";
    public static final String LIST_SESSIONS = "LIST_SESSIONS";
    public static final String KICK = "KICK";

    // server → admin: trả lời lệnh quản trị
    public static final String SENSORS = "SENSORS";
    public static final String SENSOR_KEY = "SENSOR_KEY";
    public static final String USERS = "USERS";
    public static final String SESSIONS = "SESSIONS";

    // server → client: trả lời lệnh
    public static final String CHALLENGE = "CHALLENGE";
    public static final String WELCOME = "WELCOME";
    public static final String OK = "OK";
    public static final String ERROR = "ERROR";
    public static final String HISTORY = "HISTORY";

    // server → client giám sát: tin đẩy (MOTION, BREATH, STATS, SIGNAL do Event sinh)
    public static final String PING = "PING";
    public static final String FALL = "FALL";
    public static final String SENSOR_STATUS = "SENSOR_STATUS";

    // server ↔ client cảm biến
    public static final String CONFIG = "CONFIG";
    public static final String CONFIG_OK = "CONFIG_OK";

    // mã lỗi trong ERROR
    public static final String BAD_JSON = "BAD_JSON";
    public static final String UNKNOWN_TYPE = "UNKNOWN_TYPE";
    public static final String INVALID_STATE = "INVALID_STATE";
    public static final String BAD_ARG = "BAD_ARG";
    public static final String SENSOR_OFFLINE = "SENSOR_OFFLINE";
    public static final String DUPLICATE_SENSOR = "DUPLICATE_SENSOR";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String AUTH_FAILED = "AUTH_FAILED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String TOO_MANY_ATTEMPTS = "TOO_MANY_ATTEMPTS";
    public static final String KICKED = "KICKED";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Msg() {
    }

    /** {"t":"HELLO","ver":1,"name":"ui-1"}: client giám sát (không ghi role thì hiểu là monitor). */
    public static String hello(String name) {
        JsonObject o = of(HELLO);
        o.addProperty("ver", VERSION);
        o.addProperty("name", name);
        return GSON.toJson(o);
    }

    /** {"t":"HELLO","ver":1,"name":"ui-1","user":"khanh"}: client giám sát đăng nhập bằng tài khoản user. */
    public static String hello(String name, String user) {
        JsonObject o = of(HELLO);
        o.addProperty("ver", VERSION);
        o.addProperty("name", name);
        if (user != null) o.addProperty("user", user);
        return GSON.toJson(o);
    }

    /** {"t":"CHALLENGE","nonce":"..","salt":"..","iter":120000}; cảm biến nhận salt rỗng, iter 0. */
    public static String challenge(String nonce, String salt, int iterations) {
        JsonObject o = of(CHALLENGE);
        o.addProperty("nonce", nonce);
        o.addProperty("salt", salt);
        o.addProperty("iter", iterations);
        return GSON.toJson(o);
    }

    /** {"t":"AUTH","proof":".."} */
    public static String auth(String proof) {
        JsonObject o = of(AUTH);
        o.addProperty("proof", proof);
        return GSON.toJson(o);
    }

    /** WELCOME kèm tài khoản đã đăng nhập và quyền (admin/viewer); sensors chỉ gồm cảm biến được xem. */
    public static String welcome(int session, Collection<Integer> sensors, Collection<Integer> online,
                                 String user, String access) {
        JsonObject o = JsonParser.parseString(welcome(session, sensors, online)).getAsJsonObject();
        if (user != null) o.addProperty("user", user);
        o.addProperty("access", access);
        return GSON.toJson(o);
    }

    /** Lệnh quản trị chỉ có loại tin và req: LIST_SENSORS, LIST_USERS, LIST_SESSIONS. */
    public static String request(String type, long req) {
        JsonObject o = of(type);
        o.addProperty("req", req);
        return GSON.toJson(o);
    }

    /** Lệnh quản trị kèm một số nguyên: NEW_SENSOR_KEY/DELETE_SENSOR_KEY {sensor}, KICK {session}. */
    public static String request(String type, long req, String key, long value) {
        JsonObject o = of(type);
        o.addProperty("req", req);
        o.addProperty(key, value);
        return GSON.toJson(o);
    }

    /** {"t":"DELETE_USER","req":3,"user":"me"} */
    public static String deleteUser(long req, String user) {
        JsonObject o = of(DELETE_USER);
        o.addProperty("req", req);
        o.addProperty("user", user);
        return GSON.toJson(o);
    }

    /**
     * {"t":"SAVE_USER","user":"me","access":"viewer","sensors":[1]|"*","salt":"..","iter":120000,"key":".."}.
     * Client tính salt và key từ mật khẩu, nên mật khẩu mới cũng không đi trên mạng.
     */
    public static String saveUser(long req, String user, String access, Collection<Integer> sensors,
                                  String salt, int iterations, String key) {
        JsonObject o = of(SAVE_USER);
        o.addProperty("req", req);
        o.addProperty("user", user);
        o.addProperty("access", access);
        if (sensors == null) o.addProperty("sensors", "*");
        else o.add("sensors", ints(sensors));
        o.addProperty("salt", salt);
        o.addProperty("iter", iterations);
        o.addProperty("key", key);
        return GSON.toJson(o);
    }

    /** {"t":"SENSOR_KEY","sensor":1,"secret":".."}: khoá mới, chỉ gửi cho admin vừa yêu cầu. */
    public static String sensorKey(int sensor, String secret) {
        JsonObject o = of(SENSOR_KEY);
        o.addProperty("sensor", sensor);
        o.addProperty("secret", secret);
        return GSON.toJson(o);
    }

    /** Bọc danh sách object vào một tin: {"t":type,field:[...]}. */
    public static String list(String type, String field, JsonArray items) {
        JsonObject o = of(type);
        o.add(field, items);
        return GSON.toJson(o);
    }

    /** {"t":"HELLO","ver":1,"role":"sensor","sensor":1}: client cảm biến. */
    public static String helloSensor(int sensor) {
        JsonObject o = of(HELLO);
        o.addProperty("ver", VERSION);
        o.addProperty("role", ROLE_SENSOR);
        o.addProperty("sensor", sensor);
        return GSON.toJson(o);
    }

    /** {"t":"SET_SENSOR","req":5,"sensor":1,"pingHz":50}: người giám sát đổi tần số lấy mẫu của cảm biến. */
    public static String setSensor(long req, int sensor, int pingHz) {
        JsonObject o = of(SET_SENSOR);
        o.addProperty("req", req);
        o.addProperty("sensor", sensor);
        o.addProperty("pingHz", pingHz);
        return GSON.toJson(o);
    }

    /** {"t":"CONFIG","req":9,"pingHz":50}: server chuyển lệnh xuống cảm biến. */
    public static String config(long req, int pingHz) {
        JsonObject o = of(CONFIG);
        o.addProperty("req", req);
        o.addProperty("pingHz", pingHz);
        return GSON.toJson(o);
    }

    /** {"t":"CONFIG_OK","req":9,"pingHz":50}: cảm biến báo đã áp dụng. */
    public static String configOk(long req, int pingHz) {
        JsonObject o = of(CONFIG_OK);
        o.addProperty("req", req);
        o.addProperty("pingHz", pingHz);
        return GSON.toJson(o);
    }

    /** {"t":"SENSOR_STATUS","sensor":1,"online":true}: server báo cảm biến vừa online hoặc offline. */
    public static String sensorStatus(int sensor, boolean online) {
        JsonObject o = of(SENSOR_STATUS);
        o.addProperty("sensor", sensor);
        o.addProperty("online", online);
        return GSON.toJson(o);
    }

    /** {"t":"WELCOME","session":7,"sensors":[1,2],"online":[1]} */
    public static String welcome(int session, Collection<Integer> sensors, Collection<Integer> online) {
        JsonObject o = of(WELCOME);
        o.addProperty("session", session);
        o.add("sensors", ints(sensors));
        o.add("online", ints(online));
        return GSON.toJson(o);
    }

    /** Thêm "req" vào một tin (câu trả lời cho lệnh có req); req &lt; 0 thì giữ nguyên. */
    public static String withReq(String json, long req) {
        if (req < 0) return json;
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        o.addProperty("req", req);
        return GSON.toJson(o);
    }

    /** Giá trị "req" của một tin đã parse; không có hoặc không phải số nguyên thì -1. */
    public static long req(JsonObject o) {
        JsonElement e = o.get("req");
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return -1;
        double d = e.getAsDouble();
        return d >= 0 && d == Math.rint(d) && d < 9.0e15 ? (long) d : -1;
    }

    /** {"t":"SUBSCRIBE","sensors":[1,2],"signal":true} */
    public static String subscribe(Collection<Integer> sensors, boolean signal) {
        JsonObject o = of(SUBSCRIBE);
        o.add("sensors", ints(sensors));
        o.addProperty("signal", signal);
        return GSON.toJson(o);
    }

    /** {"t":"GET_HISTORY","sensor":1,"limit":50} */
    public static String getHistory(int sensor, int limit) {
        JsonObject o = of(GET_HISTORY);
        o.addProperty("sensor", sensor);
        o.addProperty("limit", limit);
        return GSON.toJson(o);
    }

    /** {"t":"SET_CONFIG","sensor":1,"key":"motionThr","value":0.4} */
    public static String setConfig(int sensor, String key, double value) {
        JsonObject o = of(SET_CONFIG);
        o.addProperty("sensor", sensor);
        o.addProperty("key", key);
        o.addProperty("value", value);
        return GSON.toJson(o);
    }

    /** {"t":"ACK","id":42} */
    public static String ack(long id) {
        JsonObject o = of(ACK);
        o.addProperty("id", id);
        return GSON.toJson(o);
    }

    public static String pong() {
        return GSON.toJson(of(PONG));
    }

    public static String bye() {
        return GSON.toJson(of(BYE));
    }

    public static String ping() {
        return GSON.toJson(of(PING));
    }

    /** {"t":"WELCOME","session":7,"sensors":[1]} */
    public static String welcome(int session, Collection<Integer> sensors) {
        JsonObject o = of(WELCOME);
        o.addProperty("session", session);
        o.add("sensors", ints(sensors));
        return GSON.toJson(o);
    }

    /** {"t":"OK","re":"SUBSCRIBE"}: re là lệnh được trả lời. */
    public static String ok(String re) {
        JsonObject o = of(OK);
        o.addProperty("re", re);
        return GSON.toJson(o);
    }

    /** {"t":"ERROR","code":"BAD_ARG","msg":"..."} */
    public static String error(String code, String msg) {
        JsonObject o = of(ERROR);
        o.addProperty("code", code);
        o.addProperty("msg", msg);
        return GSON.toJson(o);
    }

    /**
     * {"t":"HISTORY","sensor":1,"events":[{...},{...}]}. events là JSON của từng Event
     * (Event.toJson()), được nhúng nguyên dạng object chứ không bọc thành chuỗi.
     */
    public static String history(int sensor, List<String> eventJsons) {
        JsonObject o = of(HISTORY);
        o.addProperty("sensor", sensor);
        JsonArray a = new JsonArray();
        for (String e : eventJsons) a.add(JsonParser.parseString(e));
        o.add("events", a);
        return GSON.toJson(o);
    }

    /**
     * Parse một tin nhận được. Trả null nếu không phải JSON object hoặc thiếu trường "t" dạng chuỗi,
     * để bên nhận trả ERROR BAD_JSON thay vì ném lỗi làm chết luồng đọc.
     */
    public static JsonObject parse(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) return null;
            JsonObject o = e.getAsJsonObject();
            JsonElement t = o.get("t");
            if (t == null || !t.isJsonPrimitive() || !t.getAsJsonPrimitive().isString()) return null;
            return o;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    /** Loại tin của một object đã qua {@link #parse}. */
    public static String type(JsonObject o) {
        return o.get("t").getAsString();
    }

    private static JsonObject of(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("t", type);
        return o;
    }

    private static JsonArray ints(Collection<Integer> xs) {
        JsonArray a = new JsonArray();
        for (int x : xs) a.add(x);
        return a;
    }
}
