package vn.wisense.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Sends synthetic CSI or CSV samples to the WiSense UDP receiver. */
public final class ReplayMain {
    private static final int DEFAULT_PORT = 5005;
    private static final int DEFAULT_INTERVAL_MS = 10;
    private static final int SUBCARRIERS = 64;
    private ReplayMain() {}

    public static void main(String[] args) throws Exception {
        String host = stringArg(args, "--target", "127.0.0.1");
        int port = intArg(args, "--port", DEFAULT_PORT);
        int intervalMs = intArg(args, "--interval-ms", DEFAULT_INTERVAL_MS);
        long durationSeconds = longArg(args, "--duration-seconds", 0);
        String file = stringArg(args, "--file", "");
        if (port < 1 || port > 65_535 || intervalMs < 1 || durationSeconds < 0)
            throw new IllegalArgumentException("Invalid port, interval or duration");

        InetAddress address = InetAddress.getByName(host);
        long endNanos = durationSeconds == 0 ? Long.MAX_VALUE : System.nanoTime() + durationSeconds * 1_000_000_000L;
        try (DatagramSocket socket = new DatagramSocket()) {
            System.out.printf("Replaying CSI to %s:%d every %d ms%s%n", host, port, intervalMs,
                    file.isBlank() ? " (synthetic signal)" : " from " + file);
            if (!file.isBlank()) replayCsv(socket, address, port, intervalMs, endNanos, Path.of(file));
            else replaySynthetic(socket, address, port, intervalMs, endNanos);
        }
    }

    private static void replaySynthetic(DatagramSocket socket, InetAddress address, int port,
                                        int intervalMs, long endNanos) throws IOException, InterruptedException {
        long seq = 0;
        long start = System.nanoTime();
        while (System.nanoTime() < endNanos) {
            double t = (System.nanoTime() - start) / 1_000_000_000.0;
            double breath = 3.0 * Math.sin(2 * Math.PI * 0.25 * t); // 15 breaths/min, exaggerated for visibility
            double fallPulse = (t % 30.0 < 0.5) ? 18.0 : 0.0;
            byte[] packet = syntheticPacket(1, seq++, (long) (t * 1_000_000), -42, breath, fallPulse);
            send(socket, address, port, packet);
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
            double amp = 24 + breath + fallPulse + noise;
            int imag = clampSignedByte((int) Math.round(amp * Math.sin(phase)));
            int real = clampSignedByte((int) Math.round(amp * Math.cos(phase)));
            out.put((byte) imag).put((byte) real);
        }
        return out.array();
    }

    private static void replayCsv(DatagramSocket socket, InetAddress address, int port, int intervalMs,
                                  long endNanos, Path path) throws IOException, InterruptedException {
        long sent = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while (System.nanoTime() < endNanos && (line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] columns = trimmed.split(",");
                if (!isInteger(columns[0].trim())) continue; // allows a CSV header
                byte[] packet = csvRowToPacket(columns);
                send(socket, address, port, packet);
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

    private static void send(DatagramSocket socket, InetAddress address, int port, byte[] packet) throws IOException {
        socket.send(new DatagramPacket(packet, packet.length, address, port));
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
