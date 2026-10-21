package vn.wisense.tools;

import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import vn.wisense.common.auth.Credentials;
import vn.wisense.common.protocol.FrameCodec;
import vn.wisense.common.protocol.Msg;

/**
 * Giả làm cảm biến: phát CSI tổng hợp hoặc phát lại file CSV.
 * Mặc định gửi UDP tới cổng 5005 (kênh dự phòng). Với --tcp, đóng vai client cảm biến TCP như ESP32:
 * HELLO role=sensor, gửi khung nhị phân CSI1, nhận CONFIG từ server thì đổi tốc độ phát và trả CONFIG_OK.
 *
 * <pre>
 * java -jar wisense-tools.jar --tcp --key=...                # cảm biến 1, tín hiệu tổng hợp, TCP 127.0.0.1:7000
 * java -jar wisense-tools.jar --tcp --sensor=2 --key=... --target=192.168.1.10
 * java -jar wisense-tools.jar --tcp --file=data/fall_01.csv
 * java -jar wisense-tools.jar                                # UDP 127.0.0.1:5005 như cũ
 * </pre>
 * --key là khoá bí mật của cảm biến trong data/sensors.csv của server (tạo bằng UserAdmin --add-sensor);
 * server chạy --no-auth thì không cần.
 */
public final class ReplayMain {
    private static final int DEFAULT_UDP_PORT = 5005;
    private static final int DEFAULT_TCP_PORT = 7000;
    private static final int DEFAULT_INTERVAL_MS = 10;
    private static final int SUBCARRIERS = 64;
    /** Khoảng giữa hai gói; server đổi được qua CONFIG khi chạy --tcp. */
    private static volatile int intervalMs = DEFAULT_INTERVAL_MS;
    private ReplayMain() {}

    /** Nơi gửi gói: UDP tới một địa chỉ, hoặc kết nối TCP của client cảm biến. */
    private interface Sender extends AutoCloseable {
        void send(byte[] packet) throws IOException;

        @Override void close() throws IOException;
    }

    public static void main(String[] args) throws Exception {
        boolean tcp = java.util.Arrays.asList(args).contains("--tcp");
        String host = stringArg(args, "--target", "127.0.0.1");
        int port = intArg(args, "--port", tcp ? DEFAULT_TCP_PORT : DEFAULT_UDP_PORT);
        int sensor = intArg(args, "--sensor", 1);
        intervalMs = intArg(args, "--interval-ms", DEFAULT_INTERVAL_MS);
        long durationSeconds = longArg(args, "--duration-seconds", 0);
        String file = stringArg(args, "--file", "");
        if (port < 1 || port > 65_535 || intervalMs < 1 || durationSeconds < 0 || sensor < 0 || sensor > 255)
            throw new IllegalArgumentException("Invalid port, interval, duration or sensor");

        long endNanos = durationSeconds == 0 ? Long.MAX_VALUE : System.nanoTime() + durationSeconds * 1_000_000_000L;
        String key = stringArg(args, "--key", "");
        try (Sender sender = tcp ? new TcpSender(host, port, key) : udpSender(host, port)) {
            System.out.printf("Replaying CSI to %s %s:%d every %d ms%s%n", tcp ? "TCP" : "UDP", host, port, intervalMs,
                    file.isBlank() ? " (synthetic signal, sensor " + sensor + ")" : " from " + file);
            if (!file.isBlank()) replayCsv(sender, endNanos, Path.of(file));
            else replaySynthetic(sender, sensor, endNanos);
        }
    }

    private static Sender udpSender(String host, int port) throws IOException {
        InetAddress address = InetAddress.getByName(host);
        DatagramSocket socket = new DatagramSocket();
        return new Sender() {
            public void send(byte[] packet) throws IOException {
                socket.send(new DatagramPacket(packet, packet.length, address, port));
            }

            public void close() {
                socket.close();
            }
        };
    }

    /**
     * Client cảm biến TCP. Kết nối và HELLO ở gói đầu tiên (lúc đó mới biết mã cảm biến của file CSV),
     * một luồng nền đọc lệnh CONFIG từ server.
     */
    private static final class TcpSender implements Sender {
        private final String host;
        private final int port;
        private final String key;                       // rỗng: server không bật xác thực
        private Socket socket;
        private DataOutputStream out;

        TcpSender(String host, int port, String key) {
            this.host = host;
            this.port = port;
            this.key = key;
        }

        @Override public synchronized void send(byte[] packet) throws IOException {
            if (socket == null) connect(Byte.toUnsignedInt(packet[4]));
            FrameCodec.writeFrame(out, packet);
        }

        private void connect(int sensor) throws IOException {
            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            FrameCodec.writeFrame(out, Msg.helloSensor(sensor));
            JsonObject reply = Msg.parse(FrameCodec.readFrame(in));
            if (reply != null && Msg.CHALLENGE.equals(Msg.type(reply))) {
                if (key.isBlank()) throw new IOException("Server yeu cau xac thuc: chay voi --key=<khoa cam bien " + sensor + ">");
                String proof = Credentials.sensor(key).answer(reply.get("nonce").getAsString(), "", 0);
                FrameCodec.writeFrame(out, Msg.auth(proof));
                reply = Msg.parse(FrameCodec.readFrame(in));
            }
            if (reply == null || !Msg.WELCOME.equals(Msg.type(reply)))
                throw new IOException("Server tu choi cam bien " + sensor + ": " + reply);
            System.out.println("Da dang ki cam bien " + sensor + " voi server qua TCP");
            Thread reader = new Thread(() -> readCommands(in), "replay-commands");
            reader.setDaemon(true);
            reader.start();
        }

        /** Lệnh từ server: CONFIG đổi tần số phát rồi trả CONFIG_OK. */
        private void readCommands(DataInputStream in) {
            try {
                while (true) {
                    JsonObject o = Msg.parse(FrameCodec.readFrame(in));
                    if (o == null || !Msg.CONFIG.equals(Msg.type(o))) continue;
                    int hz = o.get("pingHz").getAsInt();
                    intervalMs = Math.max(1, 1000 / hz);
                    System.out.println("Server yeu cau " + hz + " goi/giay, khoang cach moi " + intervalMs + " ms");
                    synchronized (this) {
                        FrameCodec.writeFrame(out, Msg.configOk(Msg.req(o), hz));
                    }
                }
            } catch (IOException e) {
                System.out.println("Mat ket noi toi server: " + e.getMessage());
            }
        }

        @Override public void close() throws IOException {
            if (socket != null) socket.close();
        }
    }

    private static void replaySynthetic(Sender sender, int sensor, long endNanos) throws IOException, InterruptedException {
        long seq = 0;
        long start = System.nanoTime();
        while (System.nanoTime() < endNanos) {
            double t = (System.nanoTime() - start) / 1_000_000_000.0;
            double breath = 3.0 * Math.sin(2 * Math.PI * 0.25 * t); // 15 breaths/min, exaggerated for visibility
            double fallPulse = (t % 30.0 >= 20.0 && t % 30.0 < 20.5) ? 10.0 : 0.0;   // 0,5 s mỗi 30 s, sau 20 s yên
            byte[] packet = syntheticPacket(sensor, seq++, (long) (t * 1_000_000), -42, breath, fallPulse);
            sender.send(packet);
            Thread.sleep(intervalMs);
        }
    }

    private static byte[] syntheticPacket(int sensorId, long seq, long timestampUs, int rssi,
                                         double breath, double fallPulse) {
        ByteBuffer out = ByteBuffer.allocate(20 + SUBCARRIERS * 2).order(ByteOrder.BIG_ENDIAN);
        out.put(new byte[]{'C','S','I','1'}).put((byte) sensorId).putInt((int) seq)
                .putLong(timestampUs).put((byte) rssi).putShort((short) SUBCARRIERS);
        for (int k = 0; k < SUBCARRIERS; k++) {
            double phase = 0.1 * k;
            double noise = (Math.random() - 0.5) * 1.2;
            // Cú ngã làm sóng mang con chẵn tăng, lẻ giảm: trung bình gần như không đổi như chuyển động thật,
            // nên FallDetector (chuẩn hoá theo trung bình từng khung) vẫn thấy được.
            double fall = (k % 2 == 0 ? 1 : -1) * fallPulse;
            double amp = 24 + breath + fall + noise;
            int imag = clampSignedByte((int) Math.round(amp * Math.sin(phase)));
            int real = clampSignedByte((int) Math.round(amp * Math.cos(phase)));
            out.put((byte) imag).put((byte) real);
        }
        return out.array();
    }

    private static void replayCsv(Sender sender, long endNanos, Path path) throws IOException, InterruptedException {
        long sent = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while (System.nanoTime() < endNanos && (line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] columns = trimmed.split(",");
                if (!isInteger(columns[0].trim())) continue; // allows a CSV header
                byte[] packet = csvRowToPacket(columns);
                sender.send(packet);
                sent++;
                Thread.sleep(intervalMs);
            }
        }
        System.out.println("CSV replay finished; rows sent: " + sent);
    }

    /** CSV columns: sensorId,seq,timestampUs,rssi,imag0,real0,imag1,real1,... */
    private static byte[] csvRowToPacket(String[] c) {
        if (c.length < 6 || (c.length - 4) % 2 != 0)
            throw new IllegalArgumentException("CSV row needs sensorId,seq,timestampUs,rssi and imag/real pairs");
        int sensorId = Integer.parseInt(c[0].trim());
        long seq = Long.parseLong(c[1].trim());
        long timestampUs = Long.parseLong(c[2].trim());
        int rssi = Integer.parseInt(c[3].trim());
        int n = (c.length - 4) / 2;
        if (sensorId < 0 || sensorId > 255 || rssi < -128 || rssi > 127 || n > 4096)
            throw new IllegalArgumentException("CSV row contains out-of-range values");
        ByteBuffer out = ByteBuffer.allocate(20 + 2 * n).order(ByteOrder.BIG_ENDIAN);
        out.put(new byte[]{'C','S','I','1'}).put((byte) sensorId).putInt((int) seq)
                .putLong(timestampUs).put((byte) rssi).putShort((short) n);
        for (int i = 4; i < c.length; i++) {
            int v = Integer.parseInt(c[i].trim());
            if (v < -128 || v > 127) throw new IllegalArgumentException("CSI imaginary/real values must fit signed byte");
            out.put((byte) v);
        }
        return out.array();
    }

    private static int clampSignedByte(int value) { return Math.max(-128, Math.min(127, value)); }
    private static boolean isInteger(String s) { try { Long.parseLong(s); return true; } catch (NumberFormatException e) { return false; } }
    private static String stringArg(String[] args, String key, String fallback) {
        for (String arg : args) if (arg.startsWith(key + "=")) return arg.substring(key.length() + 1);
        return fallback;
    }
    private static int intArg(String[] args, String key, int fallback) { return (int) longArg(args, key, fallback); }
    private static long longArg(String[] args, String key, long fallback) {
        for (String arg : args) if (arg.startsWith(key + "=")) return Long.parseLong(arg.substring(key.length() + 1));
        return fallback;
    }
}
