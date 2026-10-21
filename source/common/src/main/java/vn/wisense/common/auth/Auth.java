package vn.wisense.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Phép tính dùng chung cho bắt tay xác thực (challenge–response).
 *
 * <pre>
 * client → HELLO {user}                 server → CHALLENGE {nonce, salt, iter}
 * client → AUTH {proof = HMAC-SHA256(key, nonce)}
 * </pre>
 * Người dùng: key = PBKDF2-HMAC-SHA256(mật khẩu, salt, iter), 32 byte. Server chỉ lưu key, không lưu mật khẩu.
 * Cảm biến: key = các byte UTF-8 của khoá bí mật (salt rỗng, iter = 0), để ESP32 tính được bằng mbedTLS.
 * nonce là chuỗi hex ngẫu nhiên, mỗi lần một số mới, nên proof cũ không dùng lại được (chống replay).
 * HMAC tính trên các byte ASCII của chuỗi nonce, không giải mã hex.
 */
public final class Auth {
    public static final int DEFAULT_ITERATIONS = 120_000;
    public static final int KEY_BYTES = 32;
    public static final int SALT_BYTES = 16;
    public static final int NONCE_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private Auth() {
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    /** Số ngẫu nhiên dùng một lần cho mỗi lần bắt tay. */
    public static String newNonce() {
        return hex(randomBytes(NONCE_BYTES));
    }

    public static byte[] deriveKey(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BYTES * 8);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available", e);
        }
    }

    public static byte[] sensorKey(String secret) {
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** proof gửi trong AUTH: hex của HMAC-SHA256(key, nonce). */
    public static String proof(byte[] key, String nonce) {
        return hex(hmac(key, nonce.getBytes(StandardCharsets.US_ASCII)));
    }

    /** So sánh hết mọi byte dù sai ngay byte đầu, để thời gian trả lời không lộ proof đúng được bao nhiêu. */
    public static boolean verify(byte[] key, String nonce, String proof) {
        if (key == null || proof == null) return false;
        byte[] expected = proof(key, nonce).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, proof.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    public static String hex(byte[] b) {
        return HEX.formatHex(b);
    }

    public static byte[] unhex(String s) {
        return HEX.parseHex(s);
    }
}
