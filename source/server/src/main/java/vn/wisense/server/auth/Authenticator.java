package vn.wisense.server.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import vn.wisense.common.auth.Accounts;
import vn.wisense.common.auth.Auth;

/**
 * Tạo CHALLENGE, kiểm tra AUTH, và quản lí hai file tài khoản (xem {@link Accounts}) khi admin thay đổi qua mạng.
 *
 * <p>Tài khoản không tồn tại vẫn nhận CHALLENGE với salt giả (tính từ tên, cố định trong một lần chạy) và
 * luôn thất bại ở AUTH, để người ngoài không dò được tên nào có thật qua câu trả lời của server.
 * Mọi thay đổi được ghi ngay xuống file; luồng đọc của nhiều phiên đọc song song nên dùng ConcurrentHashMap,
 * còn các thao tác ghi khoá lại để file và bộ nhớ luôn khớp.
 */
public final class Authenticator {
    /** Một lần bắt tay đang chờ AUTH: key = null nghĩa là chắc chắn thất bại. */
    public record Challenge(String nonce, String salt, int iterations, byte[] key, Access access) {}

    private final Map<String, Accounts.User> users = new ConcurrentHashMap<>();
    private final Map<Integer, String> sensorKeys = new ConcurrentHashMap<>();
    private final LoginLimiter limiter;
    private final Path usersFile;                   // null: không ghi file (test)
    private final Path sensorsFile;
    private final byte[] fakeSaltSecret = Auth.randomBytes(32);

    public Authenticator(Map<String, Accounts.User> users, Map<Integer, String> sensorKeys, LoginLimiter limiter) {
        this(users, sensorKeys, limiter, null, null);
    }

    private Authenticator(Map<String, Accounts.User> users, Map<Integer, String> sensorKeys, LoginLimiter limiter,
                          Path usersFile, Path sensorsFile) {
        this.users.putAll(users);
        this.sensorKeys.putAll(sensorKeys);
        this.limiter = limiter;
        this.usersFile = usersFile;
        this.sensorsFile = sensorsFile;
    }

    /** Đọc users.csv (bắt buộc) và sensors.csv (không có thì không cảm biến TCP nào đăng nhập được). */
    public static Authenticator load(Path usersFile, Path sensorsFile) throws IOException {
        if (!Files.exists(usersFile))
            throw new IOException("Khong thay " + usersFile.toAbsolutePath()
                    + ". Tao tai khoan bang: java -cp tools/target/wisense-tools.jar vn.wisense.tools.UserAdmin"
                    + " --add=<ten> --access=admin, hoac chay server voi --no-auth");
        Map<String, Accounts.User> users = Accounts.loadUsers(usersFile);
        Map<Integer, String> sensors = Files.exists(sensorsFile) ? Accounts.loadSensorKeys(sensorsFile) : Map.of();
        System.out.println("Xac thuc: " + users.size() + " tai khoan, " + sensors.size() + " khoa cam bien");
        return new Authenticator(users, sensors, new LoginLimiter(), usersFile, sensorsFile);
    }

    public LoginLimiter limiter() {
        return limiter;
    }

    public Challenge forUser(String name) {
        Accounts.User u = users.get(name);
        if (u == null) {
            byte[] salt = Arrays.copyOf(Auth.hmac(fakeSaltSecret, name.getBytes(StandardCharsets.UTF_8)), Auth.SALT_BYTES);
            return new Challenge(Auth.newNonce(), Auth.hex(salt), Auth.DEFAULT_ITERATIONS, null, null);
        }
        return new Challenge(Auth.newNonce(), Auth.hex(u.salt()), u.iterations(), u.key(),
                new Access(u.name(), u.isAdmin(), u.sensors()));
    }

    public Challenge forSensor(int sensor) {
        String secret = sensorKeys.get(sensor);
        return new Challenge(Auth.newNonce(), "", 0, secret == null ? null : Auth.sensorKey(secret), Access.ALL);
    }

    public boolean verify(Challenge c, String proof) {
        return Auth.verify(c.key(), c.nonce(), proof);
    }

    // ------------------------------------------------------------ quản trị (lệnh của admin qua mạng)

    /** Danh sách tài khoản, sắp theo tên. */
    public List<Accounts.User> users() {
        return new ArrayList<>(new TreeMap<>(users).values());
    }

    /**
     * Thêm hoặc thay tài khoản. Không cho tự hạ quyền mình hay làm mất admin cuối cùng,
     * để server không rơi vào trạng thái không ai quản trị được.
     */
    public synchronized void saveUser(Accounts.User u, String actingUser) throws IOException {
        Accounts.User old = users.get(u.name());
        if (old != null && old.isAdmin() && !u.isAdmin()) {
            if (u.name().equals(actingUser)) throw new IllegalArgumentException("Cannot remove your own admin rights");
            if (adminCount() <= 1) throw new IllegalArgumentException("At least one admin account is required");
        }
        users.put(u.name(), u);
        saveUsers();
    }

    /** Xoá tài khoản; trả false nếu không có. */
    public synchronized boolean deleteUser(String name, String actingUser) throws IOException {
        Accounts.User old = users.get(name);
        if (old == null) return false;
        if (name.equals(actingUser)) throw new IllegalArgumentException("Cannot delete the account you are logged in with");
        if (old.isAdmin() && adminCount() <= 1) throw new IllegalArgumentException("At least one admin account is required");
        users.remove(name);
        saveUsers();
        return true;
    }

    /** Sinh khoá ngẫu nhiên mới cho cảm biến (thay khoá cũ nếu có), trả khoá để admin nạp vào ESP32. */
    public synchronized String newSensorKey(int sensor) throws IOException {
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(Auth.randomBytes(24));
        sensorKeys.put(sensor, secret);
        saveSensorKeys();
        return secret;
    }

    public synchronized boolean deleteSensorKey(int sensor) throws IOException {
        if (sensorKeys.remove(sensor) == null) return false;
        saveSensorKeys();
        return true;
    }

    public boolean hasSensorKey(int sensor) {
        return sensorKeys.containsKey(sensor);
    }

    public List<Integer> sensorsWithKey() {
        return sensorKeys.keySet().stream().sorted().toList();
    }

    private long adminCount() {
        return users.values().stream().filter(Accounts.User::isAdmin).count();
    }

    private void saveUsers() throws IOException {
        if (usersFile != null) Accounts.saveUsers(usersFile, users());
    }

    private void saveSensorKeys() throws IOException {
        if (sensorsFile != null) Accounts.saveSensorKeys(sensorsFile, new TreeMap<>(sensorKeys));
    }
}
