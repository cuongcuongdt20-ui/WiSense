package vn.wisense.common.auth;

import java.util.Arrays;

/**
 * Thông tin đăng nhập phía client, dùng để trả lời CHALLENGE.
 *
 * <p>Người dùng: lần trả lời đầu tiên tính key bằng PBKDF2 rồi xoá mật khẩu khỏi bộ nhớ; các lần nối lại sau
 * dùng key đã tính (cùng salt). Server đổi salt (mật khẩu bị đặt lại) thì AUTH thất bại, người dùng đăng nhập lại.
 * Dùng chung được giữa nhiều luồng (LoadTestClient mở nhiều kết nối cùng một tài khoản).
 */
public final class Credentials {
    private final String user;                  // null với cảm biến
    private char[] password;
    private byte[] key;
    private String keySalt;
    private int keyIterations;

    private Credentials(String user, char[] password, byte[] key) {
        this.user = user;
        this.password = password;
        this.key = key;
    }

    public static Credentials user(String user, char[] password) {
        return new Credentials(user, password.clone(), null);
    }

    public static Credentials sensor(String secret) {
        return new Credentials(null, null, Auth.sensorKey(secret));
    }

    /** Tên người dùng gửi trong HELLO; null với cảm biến. */
    public String user() {
        return user;
    }

    /** proof cho một CHALLENGE {nonce, salt, iter}. */
    public synchronized String answer(String nonce, String salt, int iterations) {
        if (user == null) return Auth.proof(key, nonce);
        if (key == null || !salt.equals(keySalt) || iterations != keyIterations) {
            if (password == null) throw new IllegalStateException("password already cleared; log in again");
            key = Auth.deriveKey(password, Auth.unhex(salt), iterations);
            keySalt = salt;
            keyIterations = iterations;
            Arrays.fill(password, '\0');
            password = null;
        }
        return Auth.proof(key, nonce);
    }
}
