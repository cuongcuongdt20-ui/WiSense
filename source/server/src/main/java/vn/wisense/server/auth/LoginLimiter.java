package vn.wisense.server.auth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Chống dò mật khẩu: một địa chỉ IP sai quá {@link #MAX_FAILURES} lần trong {@link #WINDOW_MS}
 * thì bị chặn {@link #BLOCK_MS}; trong lúc bị chặn, HELLO nhận TOO_MANY_ATTEMPTS.
 * Đăng nhập đúng thì xoá số lần sai của IP đó.
 */
public final class LoginLimiter {
    public static final int MAX_FAILURES = 5;
    public static final long WINDOW_MS = 60_000;
    public static final long BLOCK_MS = 60_000;

    private static final class Entry {
        final Deque<Long> failures = new ArrayDeque<>();
        long blockedUntil;
    }

    private final Map<String, Entry> byAddress = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public LoginLimiter() {
        this(System::currentTimeMillis);
    }

    /** clock đưa vào được để test không phải chờ thật. */
    public LoginLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    public boolean isBlocked(String address) {
        Entry e = byAddress.get(address);
        if (e == null) return false;
        synchronized (e) {
            return clock.getAsLong() < e.blockedUntil;
        }
    }

    public void failure(String address) {
        Entry e = byAddress.computeIfAbsent(address, a -> new Entry());
        long now = clock.getAsLong();
        synchronized (e) {
            e.failures.addLast(now);
            while (!e.failures.isEmpty() && now - e.failures.peekFirst() > WINDOW_MS) e.failures.removeFirst();
            if (e.failures.size() >= MAX_FAILURES) {
                e.blockedUntil = now + BLOCK_MS;
                e.failures.clear();
                System.err.println("Chan dang nhap tu " + address + " trong " + BLOCK_MS / 1000 + " giay");
            }
        }
    }

    public void success(String address) {
        byAddress.remove(address);
    }
}
