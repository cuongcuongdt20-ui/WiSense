package vn.wisense.server.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LoginLimiterTest {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final LoginLimiter limiter = new LoginLimiter(now::get);

    @Test
    void chanSauNamLanSaiRoiMoLai() {
        for (int i = 0; i < LoginLimiter.MAX_FAILURES - 1; i++) limiter.failure("10.0.0.1");
        assertFalse(limiter.isBlocked("10.0.0.1"));
        limiter.failure("10.0.0.1");
        assertTrue(limiter.isBlocked("10.0.0.1"));
        assertFalse(limiter.isBlocked("10.0.0.2"));          // chỉ chặn đúng IP đó
        now.addAndGet(LoginLimiter.BLOCK_MS + 1);
        assertFalse(limiter.isBlocked("10.0.0.1"));
    }

    @Test
    void lanSaiCuQuaCuaSoKhongTinh() {
        for (int i = 0; i < LoginLimiter.MAX_FAILURES - 1; i++) limiter.failure("10.0.0.1");
        now.addAndGet(LoginLimiter.WINDOW_MS + 1);
        limiter.failure("10.0.0.1");
        assertFalse(limiter.isBlocked("10.0.0.1"));
    }

    @Test
    void dangNhapDungXoaSoLanSai() {
        for (int i = 0; i < LoginLimiter.MAX_FAILURES - 1; i++) limiter.failure("10.0.0.1");
        limiter.success("10.0.0.1");
        limiter.failure("10.0.0.1");
        assertFalse(limiter.isBlocked("10.0.0.1"));
    }
}
