package vn.wisense.common.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sự kiện để server gửi tới client qua TCP (JSON). {@code type} là trường "t",
 * {@code sensorId} là trường "sensor", {@code fields} là các trường còn lại theo đúng thứ tự thêm vào.
 * Dùng các hàm tạo sẵn bên dưới để ra đúng định dạng của hợp đồng.
 */
public record Event(String type, int sensorId, Map<String, Object> fields) {

    public Event {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** {"t":"FALL","sensor":1,"time":1739000000,"confidence":0.8} */
    public static Event fall(int sensorId, long timeSec, double confidence) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("time", timeSec);
        f.put("confidence", confidence);
        return new Event("FALL", sensorId, f);
    }

    /** {"t":"MOTION","sensor":1,"level":0.73,"moving":true} */
    public static Event motion(int sensorId, double level, boolean moving) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("level", level);
        f.put("moving", moving);
        return new Event("MOTION", sensorId, f);
    }

    /** {"t":"BREATH","sensor":1,"bpm":15.2,"snr":4.1} */
    public static Event breath(int sensorId, double bpm, double snr) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("bpm", bpm);
        f.put("snr", snr);
        return new Event("BREATH", sensorId, f);
    }

    /** Lấy một trường số, ném lỗi nếu không có hoặc không phải số. */
    public double num(String key) {
        return ((Number) fields.get(key)).doubleValue();
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"t\":");
        quote(sb, type);
        sb.append(",\"sensor\":").append(sensorId);
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            sb.append(',');
            quote(sb, e.getKey());
            sb.append(':');
            value(sb, e.getValue());
        }
        return sb.append('}').toString();
    }

    private static void value(StringBuilder sb, Object v) {
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) d = 0;
            sb.append(Math.round(d * 100) / 100.0);          // 2 chữ số thập phân, không dùng ký hiệu khoa học
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else {
            quote(sb, String.valueOf(v));
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }
}
