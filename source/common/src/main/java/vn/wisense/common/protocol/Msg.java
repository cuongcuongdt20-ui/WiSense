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
 * Tên loại tin, mã lỗi và các hàm tạo JSON cho giao thức TCP hai chiều.
 * Mọi tin đều có trường "t" là loại tin; server và client cùng dùng để không gõ lệch tên.
 */
public final class Msg {
    public static final int VERSION = 1;

    // client → server
    public static final String HELLO = "HELLO";
    public static final String SUBSCRIBE = "SUBSCRIBE";
    public static final String GET_HISTORY = "GET_HISTORY";
    public static final String SET_CONFIG = "SET_CONFIG";
    public static final String ACK = "ACK";
    public static final String PONG = "PONG";
    public static final String BYE = "BYE";

    // server → client: trả lời lệnh
    public static final String WELCOME = "WELCOME";
    public static final String OK = "OK";
    public static final String ERROR = "ERROR";
    public static final String HISTORY = "HISTORY";

    // server → client: tin đẩy (MOTION, BREATH, STATS, SIGNAL do Event sinh)
    public static final String PING = "PING";
    public static final String FALL = "FALL";

    // mã lỗi trong ERROR
    public static final String BAD_JSON = "BAD_JSON";
    public static final String UNKNOWN_TYPE = "UNKNOWN_TYPE";
    public static final String INVALID_STATE = "INVALID_STATE";
    public static final String BAD_ARG = "BAD_ARG";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Msg() {
    }

    /** {"t":"HELLO","ver":1,"name":"ui-1"} */
    public static String hello(String name) {
        JsonObject o = of(HELLO);
        o.addProperty("ver", VERSION);
        o.addProperty("name", name);
        return GSON.toJson(o);
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
