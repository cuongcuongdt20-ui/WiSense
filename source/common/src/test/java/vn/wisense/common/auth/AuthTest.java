package vn.wisense.common.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthTest {
    @Test
    void proofDungThiVerifyDungSaiKhoaHoacNonceThiSai() {
        byte[] key = Auth.sensorKey("secret");
        String nonce = Auth.newNonce();
        String proof = Auth.proof(key, nonce);
        assertTrue(Auth.verify(key, nonce, proof));
        assertTrue(Auth.verify(key, nonce, proof.toUpperCase()));
        assertFalse(Auth.verify(Auth.sensorKey("other"), nonce, proof));
        assertFalse(Auth.verify(key, Auth.newNonce(), proof));
        assertFalse(Auth.verify(null, nonce, proof));
        assertFalse(Auth.verify(key, nonce, null));
    }

    @Test
    void hmacKhopVectorChuanRfc4231() {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?"
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                Auth.hex(Auth.hmac("Jefe".getBytes(), "what do ya want for nothing?".getBytes())));
    }

    @Test
    void nonceMoiLanMotKhac() {
        assertNotEquals(Auth.newNonce(), Auth.newNonce());
        assertEquals(Auth.NONCE_BYTES * 2, Auth.newNonce().length());
    }

    @Test
    void credentialsNguoiDungTinhKeyMotLanRoiDungLai() {
        byte[] salt = Auth.randomBytes(Auth.SALT_BYTES);
        byte[] key = Auth.deriveKey("pass123".toCharArray(), salt, 1000);
        Credentials c = Credentials.user("khanh", "pass123".toCharArray());
        String n1 = Auth.newNonce(), n2 = Auth.newNonce();
        assertEquals(Auth.proof(key, n1), c.answer(n1, Auth.hex(salt), 1000));
        assertEquals(Auth.proof(key, n2), c.answer(n2, Auth.hex(salt), 1000));   // mật khẩu đã xoá, key còn
        assertThrows(IllegalStateException.class, () -> c.answer(n1, Auth.hex(Auth.randomBytes(16)), 1000));
    }

    @Test
    void credentialsCamBien() {
        String nonce = Auth.newNonce();
        Credentials c = Credentials.sensor("key-one");
        assertNull(c.user());
        assertEquals(Auth.proof(Auth.sensorKey("key-one"), nonce), c.answer(nonce, "", 0));
    }

    @Test
    void ghiDocFileTaiKhoan(@TempDir Path dir) throws Exception {
        Accounts.User admin = Accounts.newUser("khanh", Accounts.ADMIN, null, "admin-pass".toCharArray());
        Accounts.User viewer = Accounts.newUser("me", Accounts.VIEWER, Set.of(2, 1), "viewer-pass".toCharArray());
        Path users = dir.resolve("users.csv");
        Accounts.saveUsers(users, List.of(admin, viewer));
        Map<String, Accounts.User> back = Accounts.loadUsers(users);
        assertEquals(2, back.size());
        assertArrayEquals(admin.key(), back.get("khanh").key());
        assertTrue(back.get("khanh").canSee(42));
        assertTrue(back.get("me").canSee(1));
        assertFalse(back.get("me").canSee(3));
        assertFalse(back.get("me").isAdmin());
        assertTrue(Files.readString(users).contains("me,viewer,1;2,"));
        assertFalse(Files.readString(users).contains("viewer-pass"));   // không lưu mật khẩu

        Path sensors = dir.resolve("sensors.csv");
        Accounts.saveSensorKeys(sensors, Map.of(1, "abc"));
        assertEquals(Map.of(1, "abc"), Accounts.loadSensorKeys(sensors));
    }

    @Test
    void tenVaQuyenKhongHopLeBiTuChoi() {
        assertThrows(IllegalArgumentException.class,
                () -> Accounts.newUser("a,b", Accounts.ADMIN, null, "123456".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> Accounts.newUser("khanh", "root", null, "123456".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> Accounts.newUser("khanh", Accounts.ADMIN, null, "123".toCharArray()));
    }
}
