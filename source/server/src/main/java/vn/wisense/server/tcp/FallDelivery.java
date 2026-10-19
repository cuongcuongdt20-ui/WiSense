package vn.wisense.server.tcp;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Gửi FALL tin cậy: mỗi FALL được giữ cho tới khi client ACK; quá 3 giây chưa ACK thì gửi lại,
 * tối đa 5 lần. Mục chờ lưu theo tên client (trường name của HELLO) chứ không theo phiên,
 * nên client rớt mạng rồi nối lại vẫn nhận được FALL đã lỡ.
 */
public final class FallDelivery implements AutoCloseable {
    public static final long RESEND_AFTER_MS = 3000;
    public static final int MAX_TRIES = 5;
    public static final long MAX_AGE_MS = 5 * 60_000;      // client không quay lại sau 5 phút thì bỏ

    /** Phía nhận FALL; ClientSession cài interface này. */
    public interface Recipient {
        /** Tên trong HELLO; null khi phiên chưa HELLO. */
        String name();

        /** Phiên đã SUBSCRIBE sensor này. */
        boolean wants(int sensor);

        /** Xếp tin vào hàng đợi gửi; false khi hàng đợi đầy hoặc phiên đã đóng. */
        boolean send(String json);
    }

    private static final class Pending {
        final String json;
        final long createdMs;
        long lastSentMs;
        int tries;

        Pending(String json, long now) {
            this.json = json;
            this.createdMs = now;
        }
    }

    private final Supplier<? extends Collection<? extends Recipient>> recipients;
    private final LongSupplier clock;
    private final Map<String, Map<Long, Pending>> pending = new ConcurrentHashMap<>();
    private ScheduledExecutorService timer;

    public FallDelivery(Supplier<? extends Collection<? extends Recipient>> recipients) {
        this(recipients, System::currentTimeMillis);
    }

    /** clock đưa vào được để test không phải chờ thật. */
    public FallDelivery(Supplier<? extends Collection<? extends Recipient>> recipients, LongSupplier clock) {
        this.recipients = recipients;
        this.clock = clock;
    }

    /** Chạy luồng hẹn giờ gọi {@link #tick()} mỗi giây. */
    public void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wisense-fall-delivery");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    /** Có FALL mới (json đã có id): tạo mục chờ cho mọi client đang đăng ký sensor đó rồi gửi ngay. */
    public void onFall(int sensor, long id, String json) {
        long now = clock.getAsLong();
        for (Recipient r : recipients.get()) {
            String name = r.name();
            if (name == null || !r.wants(sensor)) continue;
            Pending p = new Pending(json, now);
            pending.computeIfAbsent(name, k -> new ConcurrentHashMap<>()).put(id, p);
            trySend(r, p, now);
        }
    }

    /** Client vừa HELLO (thường là nối lại): gửi lại ngay các FALL còn chờ của tên này. */
    public void onHello(Recipient r) {
        Map<Long, Pending> mine = r.name() == null ? null : pending.get(r.name());
        if (mine == null) return;
        long now = clock.getAsLong();
        for (Pending p : mine.values()) trySend(r, p, now);
    }

    /** Client đã nhận; ACK cho id không còn chờ (ACK trùng) thì bỏ qua. */
    public void ack(String name, long id) {
        Map<Long, Pending> mine = pending.get(name);
        if (mine != null) mine.remove(id);
    }

    /** Gửi lại mục quá hạn, bỏ mục đã gửi đủ số lần hoặc chờ quá lâu. Luồng hẹn giờ gọi mỗi giây. */
    public void tick() {
        long now = clock.getAsLong();
        for (Map.Entry<String, Map<Long, Pending>> e : pending.entrySet()) {
            Recipient online = find(e.getKey());
            e.getValue().entrySet().removeIf(pe -> {
                Pending p = pe.getValue();
                synchronized (p) {
                    if (now - p.createdMs > MAX_AGE_MS || (p.tries >= MAX_TRIES && now - p.lastSentMs >= RESEND_AFTER_MS)) {
                        System.err.printf("Bo FALL id=%d cho %s sau %d lan gui%n", pe.getKey(), e.getKey(), p.tries);
                        return true;
                    }
                }
                if (online != null && now - p.lastSentMs >= RESEND_AFTER_MS) trySend(online, p, now);
                return false;
            });
        }
        pending.values().removeIf(Map::isEmpty);
    }

    /** Số FALL đang chờ ACK của một client (để test và để log). */
    public int pendingCount(String name) {
        Map<Long, Pending> mine = pending.get(name);
        return mine == null ? 0 : mine.size();
    }

    private void trySend(Recipient r, Pending p, long now) {
        synchronized (p) {
            if (p.tries >= MAX_TRIES) return;
            if (r.send(p.json)) {                           // hàng đợi đầy thì không tính, lần quét sau thử lại
                p.tries++;
                p.lastSentMs = now;
            }
        }
    }

    private Recipient find(String name) {
        for (Recipient r : recipients.get()) if (name.equals(r.name())) return r;
        return null;
    }

    @Override
    public void close() {
        if (timer != null) timer.shutdownNow();
    }
}
