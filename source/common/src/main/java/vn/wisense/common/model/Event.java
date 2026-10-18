package vn.wisense.common.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sự kiện / tin để server gửi tới client qua TCP (JSON). {@code type} là trường "t",
 * {@code sensorId} là trường "sensor", {@code fields} là các trường còn lại theo đúng thứ tự thêm vào.
 * Dùng các hàm tạo sẵn bên dưới để ra đúng định dạng của hợp đồng. JSON do Gson sinh ra,
 * nên giá trị trong {@code fields} có thể là số, boolean, chuỗi hoặc mảng.
 */
public record Event(String type, int sensorId, Map<String, Object> fields) {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public Event {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** {"t":"FALL","sensor":1,"time":1739000000,"confidence":0.8} */
    public static Event fall(int sensorId, long timeSec, double confidence) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("time", timeSec);
        f.put("confidence", round2(confidence));
        return new Event("FALL", sensorId, f);
    }

    /** {"t":"MOTION","sensor":1,"level":0.73,"moving":true} */
    public static Event motion(int sensorId, double level, boolean moving) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("level", round2(level));
        f.put("moving", moving);
        return new Event("MOTION", sensorId, f);
    }

    /** {"t":"BREATH","sensor":1,"bpm":15.2,"snr":4.1} */
    public static Event breath(int sensorId, double bpm, double snr) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("bpm", round2(bpm));
        f.put("snr", round2(snr));
        return new Event("BREATH", sensorId, f);
    }

    /** {"t":"STATS","sensor":1,"pps":98,"loss":0.02} */
    public static Event stats(int sensorId, int pps, double loss) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("pps", pps);
        f.put("loss", round2(loss));
        return new Event("STATS", sensorId, f);
    }

    /** {"t":"SIGNAL","sensor":1,"amp":[20.5,21.0,...]}; mảng được sao chép và làm tròn 2 chữ số. */
    public static Event signal(int sensorId, float[] amp) {
        double[] a = new double[amp == null ? 0 : amp.length];
        for (int i = 0; i < a.length; i++) a[i] = round2(amp[i]);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("amp", a);
        return new Event("SIGNAL", sensorId, f);
    }

    /** Lấy một trường số, ném lỗi nếu không có hoặc không phải số. */
    public double num(String key) {
        return ((Number) fields.get(key)).doubleValue();
    }

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", type);
        m.put("sensor", sensorId);
        m.putAll(fields);
        return GSON.toJson(m);
    }

    /** Làm tròn 2 chữ số; NaN / vô cùng thành 0 để JSON luôn hợp lệ. */
    private static double round2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return Math.round(v * 100) / 100.0;
    }
}
