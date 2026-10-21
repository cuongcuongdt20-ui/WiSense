package vn.wisense.tools;

import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import vn.wisense.common.auth.Accounts;
import vn.wisense.common.auth.Auth;

/**
 * Quản lý tài khoản và khoá cảm biến của server (hai file CSV, xem {@link Accounts}). Chạy trên máy server,
 * cùng thư mục với lúc chạy server, rồi khởi động lại server để nạp file mới.
 *
 * <pre>
 * UserAdmin --add=khanh --access=admin                 # hỏi mật khẩu, được xem mọi cảm biến
 * UserAdmin --add=me --access=viewer --see=1,2         # chỉ xem cảm biến 1 và 2
 * UserAdmin --remove=me
 * UserAdmin --add-sensor=1                             # sinh khoá cho cảm biến 1, in ra để nạp vào ESP32
 * UserAdmin --remove-sensor=1
 * UserAdmin --list
 * </pre>
 * Đổi file bằng --users=data/users.csv và --sensor-keys=data/sensors.csv (mặc định như server).
 * Không có console (chạy trong IDE) thì truyền --password=... (mật khẩu sẽ nằm trong lịch sử lệnh).
 */
public final class UserAdmin {
    private UserAdmin() {}

    public static void main(String[] args) throws Exception {
        Path usersFile = Path.of(arg(args, "--users", "data/users.csv"));
        Path sensorsFile = Path.of(arg(args, "--sensor-keys", "data/sensors.csv"));
        Map<String, Accounts.User> users = Files.exists(usersFile)
                ? new LinkedHashMap<>(Accounts.loadUsers(usersFile)) : new LinkedHashMap<>();
        Map<Integer, String> sensors = Files.exists(sensorsFile)
                ? new LinkedHashMap<>(Accounts.loadSensorKeys(sensorsFile)) : new LinkedHashMap<>();

        String add = arg(args, "--add", "");
        String remove = arg(args, "--remove", "");
        String addSensor = arg(args, "--add-sensor", "");
        String removeSensor = arg(args, "--remove-sensor", "");
        if (!add.isEmpty()) {
            String access = arg(args, "--access", Accounts.VIEWER);
            Set<Integer> see = Accounts.parseSensors(arg(args, "--see", "*").replace(',', ';'));
            char[] password = password(args, add);
            try {
                users.put(add, Accounts.newUser(add, access, see, password));
            } finally {
                Arrays.fill(password, '\0');
            }
            Accounts.saveUsers(usersFile, users.values());
            System.out.println("Da luu tai khoan " + add + " (" + access + ", cam bien " + Accounts.formatSensors(see)
                    + ") vao " + usersFile.toAbsolutePath());
        } else if (!remove.isEmpty()) {
            if (users.remove(remove) == null) throw new IllegalArgumentException("Khong co tai khoan " + remove);
            Accounts.saveUsers(usersFile, users.values());
            System.out.println("Da xoa tai khoan " + remove);
        } else if (!addSensor.isEmpty()) {
            int id = Integer.parseInt(addSensor);
            if (id < 0 || id > 255) throw new IllegalArgumentException("Ma cam bien 0..255");
            String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(Auth.randomBytes(24));
            sensors.put(id, secret);
            Accounts.saveSensorKeys(sensorsFile, sensors);
            System.out.println("Khoa cua cam bien " + id + ": " + secret);
            System.out.println("  ESP32:      idf.py menuconfig -> WiSense -> WS_SENSOR_KEY");
            System.out.println("  Gia lap:    java -jar tools/target/wisense-tools.jar --tcp --sensor=" + id + " --key=" + secret);
        } else if (!removeSensor.isEmpty()) {
            if (sensors.remove(Integer.parseInt(removeSensor)) == null)
                throw new IllegalArgumentException("Khong co khoa cho cam bien " + removeSensor);
            Accounts.saveSensorKeys(sensorsFile, sensors);
            System.out.println("Da xoa khoa cam bien " + removeSensor);
        } else if (Arrays.asList(args).contains("--list")) {
            System.out.println("Tai khoan (" + usersFile + "):");
            users.values().forEach(u -> System.out.printf("  %-16s %-7s cam bien %s%n", u.name(), u.access(),
                    Accounts.formatSensors(u.sensors())));
            System.out.println("Cam bien co khoa (" + sensorsFile + "): " + sensors.keySet());
        } else {
            System.out.println("Dung: --add=<ten> [--access=admin|viewer] [--see=*|1,2] | --remove=<ten>"
                    + " | --add-sensor=<id> | --remove-sensor=<id> | --list");
        }
    }

    /** Hỏi mật khẩu hai lần trên console, không hiện ký tự; không có console thì lấy --password. */
    private static char[] password(String[] args, String user) {
        Console console = System.console();
        String given = arg(args, "--password", "");
        if (!given.isEmpty() || console == null) {
            if (given.isEmpty()) throw new IllegalStateException("Khong co console: truyen --password=...");
            return given.toCharArray();
        }
        console.printf("(Khi go mat khau man hinh KHONG hien ky tu nao; go xong bam Enter MOT lan)%n");
        for (int attempt = 1; attempt <= 3; attempt++) {
            char[] first = console.readPassword("Mat khau cho %s (it nhat 6 ky tu): ", user);
            if (first == null) break;
            if (first.length < 6) {
                console.printf("Mat khau qua ngan (%d ky tu), nhap lai.%n", first.length);
                continue;
            }
            char[] again = console.readPassword("Nhap lai mat khau: ");
            boolean same = Arrays.equals(first, again);
            if (again != null) Arrays.fill(again, '\0');
            if (same) return first;
            Arrays.fill(first, '\0');
            console.printf("Hai lan nhap khong khop, thu lai.%n");
        }
        throw new IllegalArgumentException("Khong dat duoc mat khau; co the dung --password=...");
    }

    private static String arg(String[] args, String key, String fallback) {
        for (String a : args) if (a.startsWith(key + "=")) return a.substring(key.length() + 1);
        return fallback;
    }
}
