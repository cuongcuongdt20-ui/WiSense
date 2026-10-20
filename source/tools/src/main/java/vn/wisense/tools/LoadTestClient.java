package vn.wisense.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import vn.wisense.common.protocol.FrameCodec;
import vn.wisense.common.protocol.Msg;

/**
 * Mở N client không giao diện cùng lúc tới server để đo hiệu năng phần TCP.
 * Mỗi client là một luồng: HELLO, chờ WELCOME, SUBSCRIBE, rồi đọc tin tới hết giờ.
 * Đo: độ trễ = giờ nhận − trường ts (lúc server tạo tin), số tin và byte mỗi giây,
 * số FALL nhận được và số bản trùng, thời gian bắt tay, client có bị server ngắt không.
 * Chạy cùng máy với server để hai bên dùng chung đồng hồ.
 *
 * <pre>
 * java -cp wisense-tools.jar vn.wisense.tools.LoadTestClient --clients=50 --seconds=30 --label=c50
 * java -cp wisense-tools.jar vn.wisense.tools.LoadTestClient --clients=10 --slow-clients=1 --slow-ms=200 --label=slow
 * </pre>
 * Kết quả: mỗi client một dòng, ghi thêm vào statics/results/loadtest.csv (đổi bằng --out).
 */
public final class LoadTestClient {

    private LoadTestClient() {}

    record Config(String host, int port, int clients, int seconds, List<Integer> sensors, boolean signal,
                  int slowClients, int slowMs, boolean ack, String label, Path out) {}

    /** Kết quả một client. latencies: độ trễ từng tin có ts (ms), đã sắp tăng dần. */
    record Result(int client, boolean slow, long msgs, long bytes, long falls, long dupFalls,
                  long handshakeMs, boolean disconnected, long[] latencies) {}

    public static void main(String[] args) throws Exception {
        Config cfg = parse(args);
        System.out.printf("Mo %d client toi %s:%d trong %d giay (%d client cham %d ms/tin, ACK %s)%n",
                cfg.clients(), cfg.host(), cfg.port(), cfg.seconds(), cfg.slowClients(), cfg.slowMs(),
                cfg.ack() ? "bat" : "tat");
        List<Result> results = run(cfg);
        writeCsv(cfg, results);
        System.out.println(summary(cfg, results));
        System.out.println("Da ghi " + results.size() + " dong vao " + cfg.out());
    }

    /** Chạy cfg.clients() luồng song song, đóng mọi socket khi hết giờ, trả kết quả từng client. */
    static List<Result> run(Config cfg) throws InterruptedException {
        List<Result> results = new CopyOnWriteArrayList<>();
        List<Socket> sockets = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        long endMs = System.currentTimeMillis() + cfg.seconds() * 1000L;
        for (int i = 0; i < cfg.clients(); i++) {
            int id = i;
            boolean slow = i < cfg.slowClients();
            Thread t = new Thread(() -> results.add(runClient(cfg, id, slow, sockets)), "load-" + i);
            t.start();
            threads.add(t);
        }
        long wait = endMs - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        for (Socket s : sockets) {                          // hết giờ: đóng socket để readFrame thoát ra
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        for (Thread t : threads) t.join(5000);
        return new ArrayList<>(results);
    }

    /** Một client: trả kết quả khi socket bị đóng (hết giờ) hoặc server ngắt. */
    static Result runClient(Config cfg, int id, boolean slow, List<Socket> sockets) {
        long msgs = 0, bytes = 0, falls = 0, dups = 0, handshakeMs = -1;
        boolean disconnected = false;
        Set<Long> seen = new HashSet<>();
        LongList lat = new LongList();
        Socket s = new Socket();
        sockets.add(s);
        try {
            long t0 = System.currentTimeMillis();
            s.connect(new InetSocketAddress(cfg.host(), cfg.port()), 3000);
            s.setSoTimeout(15_000);
            s.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            FrameCodec.writeFrame(out, Msg.hello("load-" + id));
            while (true) {
                String json = FrameCodec.readFrame(in);
                long now = System.currentTimeMillis();
                JsonObject o = Msg.parse(json);
                if (o == null) continue;
                String type = Msg.type(o);
                switch (type) {
                    case Msg.WELCOME -> {
                        handshakeMs = now - t0;
                        FrameCodec.writeFrame(out, Msg.subscribe(cfg.sensors(), cfg.signal()));
                        continue;
                    }
                    case Msg.PING -> {
                        FrameCodec.writeFrame(out, Msg.pong());
                        continue;
                    }
                    case Msg.OK, Msg.ERROR -> {
                        continue;
                    }
                    case Msg.FALL -> {
                        long fid = longField(o, "id");
                        if (fid >= 0) {
                            if (cfg.ack()) FrameCodec.writeFrame(out, Msg.ack(fid));
                            if (!seen.add(fid)) {
                                dups++;
                                continue;                   // bản gửi lại: không tính độ trễ lần nữa
                            }
                        }
                        falls++;
                    }
                    default -> { }
                }
                msgs++;
                bytes += 4 + json.getBytes(StandardCharsets.UTF_8).length;
                long ts = longField(o, "ts");
                if (ts > 0) lat.add(now - ts);
                if (slow && cfg.slowMs() > 0) Thread.sleep(cfg.slowMs());
            }
        } catch (IOException e) {
            // socket bị đóng vì hết giờ là bình thường; còn lại là server ngắt hoặc không kết nối được
            disconnected = !s.isClosed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        return new Result(id, slow, msgs, bytes, falls, dups, handshakeMs, disconnected, lat.sorted());
    }

    /** Phân vị p (0..100) của mảng đã sắp, cách "nearest rank"; mảng rỗng trả -1. */
    static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) return -1;
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    static String summary(Config cfg, List<Result> results) {
        LongList normal = new LongList();
        long msgs = 0, bytes = 0, falls = 0, dups = 0, dropped = 0, nNormal = 0;
        for (Result r : results) {
            if (r.disconnected()) dropped++;
            if (r.slow()) continue;
            nNormal++;
            msgs += r.msgs();
            bytes += r.bytes();
            falls += r.falls();
            dups += r.dupFalls();
            for (long v : r.latencies()) normal.add(v);
        }
        long[] all = normal.sorted();
        double sec = cfg.seconds();
        return String.format(Locale.ROOT,
                "[%s] %d client (%d cham): moi client thuong %.1f tin/giay, %.1f KB/giay; "
                        + "do tre p50 %d ms, p95 %d ms, max %d ms; FALL %d (trung %d); bi ngat %d",
                cfg.label(), results.size(), results.size() - nNormal,
                nNormal == 0 ? 0 : msgs / sec / nNormal, nNormal == 0 ? 0 : bytes / 1024.0 / sec / nNormal,
                percentile(all, 50), percentile(all, 95), all.length == 0 ? -1 : all[all.length - 1],
                falls, dups, dropped);
    }

    static void writeCsv(Config cfg, List<Result> results) throws IOException {
        Path out = cfg.out();
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        StringBuilder sb = new StringBuilder();
        if (!Files.exists(out)) {
            sb.append("label,clients,seconds,client,slow,msgs,msgsPerSec,bytesPerSec,latP50Ms,latP95Ms,latMaxMs,"
                    + "falls,dupFalls,handshakeMs,disconnected\n");
        }
        for (Result r : results) {
            long[] l = r.latencies();
            sb.append(String.format(Locale.ROOT, "%s,%d,%d,%d,%b,%d,%.2f,%.0f,%d,%d,%d,%d,%d,%d,%b%n",
                    cfg.label(), cfg.clients(), cfg.seconds(), r.client(), r.slow(), r.msgs(),
                    r.msgs() / (double) cfg.seconds(), r.bytes() / (double) cfg.seconds(),
                    percentile(l, 50), percentile(l, 95), l.length == 0 ? -1 : l[l.length - 1],
                    r.falls(), r.dupFalls(), r.handshakeMs(), r.disconnected()));
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static Config parse(String[] args) {
        String sensors = stringArg(args, "--sensors", "1");
        List<Integer> ids = new ArrayList<>();
        for (String x : sensors.split(",")) ids.add(Integer.parseInt(x.trim()));
        int clients = (int) longArg(args, "--clients", 10);
        int seconds = (int) longArg(args, "--seconds", 30);
        if (clients < 1 || seconds < 1) throw new IllegalArgumentException("--clients va --seconds phai >= 1");
        return new Config(stringArg(args, "--host", "127.0.0.1"), (int) longArg(args, "--port", 7000), clients, seconds,
                List.copyOf(ids), !Arrays.asList(args).contains("--no-signal"),
                (int) longArg(args, "--slow-clients", 0), (int) longArg(args, "--slow-ms", 0),
                !Arrays.asList(args).contains("--no-ack"), stringArg(args, "--label", "run"),
                Path.of(stringArg(args, "--out", "statics/results/loadtest.csv")));
    }

    private static long longField(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : -1;
    }

    private static String stringArg(String[] args, String key, String fallback) {
        for (String arg : args) if (arg.startsWith(key + "=")) return arg.substring(key.length() + 1);
        return fallback;
    }

    private static long longArg(String[] args, String key, long fallback) {
        for (String arg : args) if (arg.startsWith(key + "=")) return Long.parseLong(arg.substring(key.length() + 1));
        return fallback;
    }

    /** Mảng long tự giãn, tránh hộp hóa Long khi lưu hàng chục nghìn độ trễ. */
    static final class LongList {
        private long[] a = new long[256];
        private int n;

        void add(long v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        long[] sorted() {
            long[] r = Arrays.copyOf(a, n);
            Arrays.sort(r);
            return r;
        }
    }
}
