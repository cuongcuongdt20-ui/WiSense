package vn.wisense.server.sensor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import vn.wisense.common.protocol.Msg;

/**
 * Danh sách cảm biến của server: cảm biến nào đang online, cảm biến nào đang nối bằng TCP (để gửi lệnh xuống),
 * và các lệnh SET_SENSOR đang chờ cảm biến trả lời.
 *
 * <p>Online = có khung CSI trong {@link #OFFLINE_AFTER_MS} gần nhất (áp dụng cho cả cảm biến TCP lẫn UDP).
 * Cảm biến TCP đóng kết nối thì offline ngay. Mỗi lần đổi trạng thái, mọi client giám sát nhận SENSOR_STATUS.
 */
public final class SensorRegistry implements AutoCloseable {
    public static final long OFFLINE_AFTER_MS = 5000;
    public static final long CONFIG_TIMEOUT_MS = 3000;

    /** Kết nối TCP của một cảm biến; ClientSession cài interface này. */
    public interface Link {
        boolean send(String json);

        boolean isOpen();
    }

    private static final class Entry {
        volatile Link link;
        volatile long lastFrameMs;
        volatile boolean online;
    }

    /** Lệnh đang chờ cảm biến trả CONFIG_OK: trả lời về đâu và với req nào của người giám sát. */
    private record Pending(Consumer<String> reply, long monitorReq, long deadlineMs) {}

    private final Map<Integer, Entry> sensors = new ConcurrentHashMap<>();
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextConfigId = new AtomicLong(1);
    private final Consumer<String> notifyMonitors;
    private final LongSupplier clock;
    private ScheduledExecutorService timer;

    public SensorRegistry(Consumer<String> notifyMonitors) {
        this(notifyMonitors, System::currentTimeMillis);
    }

    /** clock đưa vào được để test không phải chờ thật. */
    public SensorRegistry(Consumer<String> notifyMonitors, LongSupplier clock) {
        this.notifyMonitors = notifyMonitors;
        this.clock = clock;
    }

    /** Chạy luồng hẹn giờ gọi {@link #tick()} hai lần mỗi giây. */
    public void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wisense-sensor-registry");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(this::tick, 500, 500, TimeUnit.MILLISECONDS);
    }

    /**
     * Cảm biến TCP vừa HELLO. Trả false nếu mã này đang có một kết nối khác còn mở (trùng id):
     * kết nối mới bị từ chối, kết nối cũ giữ nguyên.
     */
    public synchronized boolean register(int sensor, Link link) {
        Entry e = sensors.computeIfAbsent(sensor, k -> new Entry());
        Link current = e.link;
        if (current != null && current != link && current.isOpen()) return false;
        e.link = link;
        touch(sensor);
        return true;
    }

    /** Kết nối TCP của cảm biến đã đóng: offline ngay, không chờ hết 5 giây. */
    public synchronized void unregister(int sensor, Link link) {
        Entry e = sensors.get(sensor);
        if (e == null || e.link != link) return;            // kết nối cũ đóng sau khi đã có kết nối mới
        e.link = null;
        setOnline(sensor, e, false);
    }

    /** Có khung CSI từ cảm biến này (qua TCP hoặc UDP). Gọi từ luồng đọc của cảm biến. */
    public void touch(int sensor) {
        Entry e = sensors.computeIfAbsent(sensor, k -> new Entry());
        e.lastFrameMs = clock.getAsLong();
        if (!e.online) {                                    // kiểm tra lại trong khoá để chỉ báo "online" một lần
            synchronized (this) {
                if (!e.online) setOnline(sensor, e, true);
            }
        }
    }

    /**
     * Chuyển SET_SENSOR xuống cảm biến dưới dạng CONFIG với mã riêng của server (tránh trùng req giữa
     * nhiều người giám sát). Trả false nếu cảm biến không có kết nối TCP còn mở.
     */
    public boolean routeConfig(int sensor, int pingHz, long monitorReq, Consumer<String> reply) {
        Entry e = sensors.get(sensor);
        Link link = e == null ? null : e.link;
        if (link == null || !link.isOpen()) return false;
        long id = nextConfigId.getAndIncrement();
        pending.put(id, new Pending(reply, monitorReq, clock.getAsLong() + CONFIG_TIMEOUT_MS));
        if (!link.send(Msg.config(id, pingHz))) {
            pending.remove(id);
            return false;
        }
        return true;
    }

    /** Cảm biến trả CONFIG_OK: báo OK về đúng người giám sát đã gửi lệnh. id lạ (đã hết hạn) thì bỏ qua. */
    public void onConfigOk(long configId, int pingHz) {
        Pending p = pending.remove(configId);
        if (p == null) return;
        p.reply().accept(Msg.withReq(okSetSensor(pingHz), p.monitorReq()));
    }

    /** Đánh dấu offline cảm biến im lặng quá lâu, trả TIMEOUT cho lệnh chờ quá hạn. */
    public void tick() {
        long now = clock.getAsLong();
        for (Map.Entry<Integer, Entry> me : sensors.entrySet()) {
            Entry e = me.getValue();
            if (e.online && now - e.lastFrameMs > OFFLINE_AFTER_MS) {
                synchronized (this) {
                    if (e.online && now - e.lastFrameMs > OFFLINE_AFTER_MS) setOnline(me.getKey(), e, false);
                }
            }
        }
        pending.entrySet().removeIf(pe -> {
            Pending p = pe.getValue();
            if (now < p.deadlineMs()) return false;
            p.reply().accept(Msg.withReq(Msg.error(Msg.TIMEOUT, "Cam bien khong tra loi CONFIG"), p.monitorReq()));
            return true;
        });
    }

    /** Mọi cảm biến server từng thấy, tăng dần. */
    public List<Integer> known() {
        List<Integer> ids = new ArrayList<>(sensors.keySet());
        ids.sort(null);
        return ids;
    }

    /** Các cảm biến đang online, tăng dần. */
    public List<Integer> online() {
        List<Integer> ids = new ArrayList<>();
        for (Map.Entry<Integer, Entry> me : sensors.entrySet()) if (me.getValue().online) ids.add(me.getKey());
        ids.sort(null);
        return ids;
    }

    /** Cảm biến đang có kết nối TCP còn mở (điều khiển được bằng SET_SENSOR). */
    public boolean isTcp(int sensor) {
        Entry e = sensors.get(sensor);
        Link link = e == null ? null : e.link;
        return link != null && link.isOpen();
    }

    /** Số lệnh đang chờ cảm biến trả lời (để test). */
    int pendingCount() {
        return pending.size();
    }

    private void setOnline(int sensor, Entry e, boolean online) {
        e.online = online;
        notifyMonitors.accept(Msg.sensorStatus(sensor, online));
    }

    private static String okSetSensor(int pingHz) {
        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(Msg.ok(Msg.SET_SENSOR)).getAsJsonObject();
        o.addProperty("pingHz", pingHz);
        return o.toString();
    }

    @Override
    public void close() {
        if (timer != null) timer.shutdownNow();
    }
}
