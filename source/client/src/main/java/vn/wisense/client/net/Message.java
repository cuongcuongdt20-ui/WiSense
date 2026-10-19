package vn.wisense.client.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Tin TCP từ server; loại tin lạ trả về null để server thêm tin mới không làm sập client. */
public sealed interface Message {
    int sensor();

    record Motion(int sensor, double level, boolean moving) implements Message {}

    record Breath(int sensor, double bpm, double snr) implements Message {}

    record Fall(int sensor, long time, double confidence) implements Message {}

    record Stats(int sensor, int pps, double loss) implements Message {}

    record Signal(int sensor, float[] amp) implements Message {}

    record Ping() implements Message {
        public int sensor() {
            return 0;
        }
    }

    static Message parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        return switch (o.get("t").getAsString()) {
            case "MOTION" -> new Motion(sensor(o), o.get("level").getAsDouble(), o.get("moving").getAsBoolean());
            case "BREATH" -> new Breath(sensor(o), o.get("bpm").getAsDouble(), o.get("snr").getAsDouble());
            case "FALL" -> new Fall(sensor(o), o.get("time").getAsLong(), o.get("confidence").getAsDouble());
            case "STATS" -> new Stats(sensor(o), o.get("pps").getAsInt(), o.get("loss").getAsDouble());
            case "SIGNAL" -> new Signal(sensor(o), floats(o.getAsJsonArray("amp")));
            case "PING" -> new Ping();
            default -> null;
        };
    }

    private static int sensor(JsonObject o) {
        return o.get("sensor").getAsInt();
    }

    private static float[] floats(JsonArray a) {
        float[] r = new float[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsFloat();
        return r;
    }
}
