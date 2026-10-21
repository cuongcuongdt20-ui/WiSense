package vn.wisense.tools;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import vn.wisense.common.protocol.CsiCsv;

/**
 * Ghi gói CSI1 từ ESP32 ra file CSV để phát lại bằng ReplayMain:
 * sensorId,seq,timestampUs,rssi,imag0,real0,imag1,real1,...
 * Giữ nguyên cặp imag/real gốc nên phát lại cho kết quả giống hệt lúc ghi.
 *
 * <pre>
 * java -cp wisense-tools.jar vn.wisense.tools.RecordMain --out=data/walk_01.csv --seconds=60
 * java -cp wisense-tools.jar vn.wisense.tools.RecordMain --out=data/fall_01.csv --port=5005 --forward=127.0.0.1:5006
 * </pre>
 * --forward chuyển tiếp từng gói cho server (chạy server với --udp-port=5006) để vừa ghi vừa xem trên client.
 */
public final class RecordMain {
    private static final int DEFAULT_PORT = 5005;
    private static final int MAX_PACKET = 65_507;

    private RecordMain() {}

    /** Kết quả một lần ghi. missing: số gói hụt theo seq, tính riêng từng cảm biến. */
    record Stats(long rows, long malformed, long missing) {}

    public static void main(String[] args) throws Exception {
        String out = stringArg(args, "--out", "");
        int port = (int) longArg(args, "--port", DEFAULT_PORT);
        long seconds = longArg(args, "--seconds", 0);
        String forward = stringArg(args, "--forward", "");
        if (out.isBlank()) throw new IllegalArgumentException("Thieu --out=duong/dan/file.csv");
        if (port < 1 || port > 65_535 || seconds < 0) throw new IllegalArgumentException("Cong hoac --seconds khong hop le");

        Path path = Path.of(out);
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        InetSocketAddress forwardTo = forward.isBlank() ? null : parseHostPort(forward);
        long endNanos = seconds == 0 ? Long.MAX_VALUE : System.nanoTime() + seconds * 1_000_000_000L;
        AtomicBoolean running = new AtomicBoolean(true);
        Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {   // Ctrl+C: dừng vòng ghi rồi chờ ghi xong file
            running.set(false);
            try {
                mainThread.join(3000);
            } catch (InterruptedException ignored) {
            }
        }, "record-shutdown"));

        try (DatagramSocket in = new DatagramSocket(port);
             DatagramSocket fwd = forwardTo == null ? null : new DatagramSocket();
             BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("# WiSense CSI, ghi luc " + LocalDateTime.now() + ", cong UDP " + port + "\n");
            System.out.printf("Dang ghi UDP :%d vao %s%s%s. Ctrl+C de dung.%n", port, path,
                    seconds > 0 ? " trong " + seconds + " giay" : "",
                    forwardTo != null ? ", chuyen tiep toi " + forward : "");
            Stats s = record(in, writer, endNanos, running, fwd, forwardTo, true);
            System.out.printf("Xong: %d goi, %d goi hong bo qua, %d goi hut theo seq.%n", s.rows(), s.malformed(), s.missing());
        }
    }

    /** Vòng ghi; tách khỏi main để test được với socket cổng ngẫu nhiên. */
    static Stats record(DatagramSocket in, BufferedWriter writer, long endNanos, AtomicBoolean running,
                        DatagramSocket fwd, InetSocketAddress forwardTo, boolean progress) throws IOException {
        in.setSoTimeout(500);                               // để kiểm tra hết giờ / Ctrl+C mỗi nửa giây
        byte[] buf = new byte[MAX_PACKET];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        Map<Integer, Long> lastSeq = new HashMap<>();
        long rows = 0, malformed = 0, missing = 0, lastReport = System.nanoTime(), rowsAtReport = 0;
        boolean wroteHeader = false;

        while (running.get() && System.nanoTime() < endNanos) {
            try {
                packet.setLength(buf.length);
                in.receive(packet);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (SocketException e) {
                break;                                      // socket bị đóng
            }
            if (fwd != null) fwd.send(new DatagramPacket(buf, packet.getLength(), forwardTo));

            String row;
            try {
                row = toCsvRow(buf, packet.getLength());
            } catch (IllegalArgumentException e) {
                malformed++;
                continue;
            }
            if (!wroteHeader) {
                writer.write(header((row.split(",").length - 4) / 2));
                wroteHeader = true;
            }
            writer.write(row);
            writer.write('\n');
            rows++;

            int sensor = Byte.toUnsignedInt(buf[4]);
            long seq = Integer.toUnsignedLong(ByteBuffer.wrap(buf, 5, 4).order(ByteOrder.BIG_ENDIAN).getInt());
            Long prev = lastSeq.put(sensor, seq);
            if (prev != null && seq > prev + 1) missing += seq - prev - 1;

            long now = System.nanoTime();
            if (progress && now - lastReport >= 1_000_000_000L) {
                System.out.printf("  %d goi (%d goi/giay), hong %d, hut %d%n", rows, rows - rowsAtReport, malformed, missing);
                lastReport = now;
                rowsAtReport = rows;
            }
        }
        writer.flush();
        return new Stats(rows, malformed, missing);
    }

    /** Đổi một gói CSI1 thành dòng CSV; gói sai định dạng thì ném IllegalArgumentException. */
    static String toCsvRow(byte[] p, int length) {
        return CsiCsv.toRow(p, length);
    }

    private static String header(int n) {
        return CsiCsv.header(n);
    }

    private static InetSocketAddress parseHostPort(String s) {
        int colon = s.lastIndexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("--forward phai co dang host:port");
        return new InetSocketAddress(s.substring(0, colon), Integer.parseInt(s.substring(colon + 1)));
    }

    private static String stringArg(String[] args, String key, String fallback) {
        for (String arg : args) if (arg.startsWith(key + "=")) return arg.substring(key.length() + 1);
        return fallback;
    }

    private static long longArg(String[] args, String key, long fallback) {
        for (String arg : args) if (arg.startsWith(key + "=")) return Long.parseLong(arg.substring(key.length() + 1));
        return fallback;
    }
}
