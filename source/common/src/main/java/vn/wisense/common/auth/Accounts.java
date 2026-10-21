package vn.wisense.common.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Hai file tài khoản của server, dạng CSV, dòng bắt đầu bằng # là chú thích.
 *
 * <pre>
 * users.csv:   name,access,sensors,iterations,salt,key      ví dụ  khanh,admin,*,120000,9f..,3c..
 * sensors.csv: sensor,secret                                ví dụ  1,Xk3...
 * </pre>
 * access là admin (xem và điều khiển) hoặc viewer (chỉ xem); sensors là * (mọi cảm biến) hoặc 1;2;3.
 * Hai file này chứa bí mật nên không được commit.
 */
public final class Accounts {
    public static final String ADMIN = "admin";
    public static final String VIEWER = "viewer";

    private Accounts() {
    }

    /** Một tài khoản; sensors = null nghĩa là được xem mọi cảm biến. */
    public record User(String name, String access, Set<Integer> sensors, int iterations, byte[] salt, byte[] key) {
        public User {
            checkName(name);
            if (!ADMIN.equals(access) && !VIEWER.equals(access))
                throw new IllegalArgumentException("access must be admin or viewer: " + access);
            sensors = sensors == null ? null : Set.copyOf(sensors);
        }

        public boolean isAdmin() {
            return ADMIN.equals(access);
        }

        public boolean canSee(int sensor) {
            return sensors == null || sensors.contains(sensor);
        }

        String toLine() {
            return String.join(",", name, access, formatSensors(sensors), Integer.toString(iterations),
                    Auth.hex(salt), Auth.hex(key));
        }

        static User parse(String line) {
            String[] f = line.split(",", -1);
            if (f.length != 6) throw new IllegalArgumentException("expected 6 fields");
            return new User(f[0].trim(), f[1].trim(), parseSensors(f[2].trim()), Integer.parseInt(f[3].trim()),
                    Auth.unhex(f[4].trim()), Auth.unhex(f[5].trim()));
        }
    }

    /** Tạo tài khoản mới: sinh salt ngẫu nhiên rồi tính key từ mật khẩu. */
    public static User newUser(String name, String access, Set<Integer> sensors, char[] password) {
        if (password.length < 6) throw new IllegalArgumentException("password must have at least 6 characters");
        byte[] salt = Auth.randomBytes(Auth.SALT_BYTES);
        return new User(name, access, sensors, Auth.DEFAULT_ITERATIONS, salt,
                Auth.deriveKey(password, salt, Auth.DEFAULT_ITERATIONS));
    }

    public static Map<String, User> loadUsers(Path file) throws IOException {
        Map<String, User> users = new LinkedHashMap<>();
        int n = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            n++;
            if (skip(line)) continue;
            try {
                User u = User.parse(line);
                users.put(u.name(), u);
            } catch (RuntimeException e) {
                throw new IOException(file + " line " + n + ": " + e.getMessage(), e);
            }
        }
        return users;
    }

    public static void saveUsers(Path file, Collection<User> users) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# name,access,sensors,iterations,salt,key  (tao bang UserAdmin, khong sua tay)");
        for (User u : users) lines.add(u.toLine());
        write(file, lines);
    }

    public static Map<Integer, String> loadSensorKeys(Path file) throws IOException {
        Map<Integer, String> keys = new LinkedHashMap<>();
        int n = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            n++;
            if (skip(line)) continue;
            int comma = line.indexOf(',');
            try {
                if (comma < 0) throw new IllegalArgumentException("expected sensor,secret");
                int id = Integer.parseInt(line.substring(0, comma).trim());
                String secret = line.substring(comma + 1).trim();
                if (id < 0 || id > 255 || secret.isEmpty()) throw new IllegalArgumentException("bad sensor or secret");
                keys.put(id, secret);
            } catch (RuntimeException e) {
                throw new IOException(file + " line " + n + ": " + e.getMessage(), e);
            }
        }
        return keys;
    }

    public static void saveSensorKeys(Path file, Map<Integer, String> keys) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# sensor,secret  (secret nap vao ESP32: menuconfig -> WS_SENSOR_KEY)");
        keys.forEach((id, secret) -> lines.add(id + "," + secret));
        write(file, lines);
    }

    /** "*" → null (mọi cảm biến), "1;2" → {1, 2}. */
    public static Set<Integer> parseSensors(String s) {
        if (s.equals("*")) return null;
        Set<Integer> r = new TreeSet<>();
        for (String part : s.split(";")) {
            if (part.isBlank()) continue;
            int id = Integer.parseInt(part.trim());
            if (id < 0 || id > 255) throw new IllegalArgumentException("sensor id out of range: " + id);
            r.add(id);
        }
        return r;
    }

    public static String formatSensors(Set<Integer> sensors) {
        if (sensors == null) return "*";
        StringBuilder b = new StringBuilder();
        for (int id : new TreeSet<>(sensors)) {
            if (!b.isEmpty()) b.append(';');
            b.append(id);
        }
        return b.toString();
    }

    static void checkName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9._-]{1,32}"))
            throw new IllegalArgumentException("name must be 1..32 characters of A-Z a-z 0-9 . _ -");
    }

    private static boolean skip(String line) {
        String t = line.strip();
        return t.isEmpty() || t.startsWith("#");
    }

    private static void write(Path file, List<String> lines) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
