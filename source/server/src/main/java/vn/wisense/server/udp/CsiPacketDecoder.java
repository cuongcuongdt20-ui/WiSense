package vn.wisense.server.udp;

import vn.wisense.common.model.CsiFrame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Decodes the WiSense CSI1 UDP packet contract (big-endian). */
public final class CsiPacketDecoder {
    private static final byte[] MAGIC = "CSI1".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_BYTES = 20;
    private static final int MAX_SUBCARRIERS = 4096;

    private CsiPacketDecoder() {}

    public static CsiFrame decode(byte[] packet, int length) throws PacketFormatException {
        if (packet == null) throw new PacketFormatException("Packet is null");
        if (length < HEADER_BYTES || length > packet.length)
            throw new PacketFormatException("Invalid packet length: " + length);
        for (int i = 0; i < MAGIC.length; i++) {
            if (packet[i] != MAGIC[i]) throw new PacketFormatException("Invalid magic; expected CSI1");
        }

        ByteBuffer in = ByteBuffer.wrap(packet, 0, length).order(ByteOrder.BIG_ENDIAN);
        in.position(4);
        int sensorId = Byte.toUnsignedInt(in.get());
        long seq = Integer.toUnsignedLong(in.getInt());
        long timestampUs = in.getLong();
        int rssi = in.get(); // signed byte by contract
        int n = Short.toUnsignedInt(in.getShort());
        if (n < 1 || n > MAX_SUBCARRIERS)
            throw new PacketFormatException("Invalid subcarrier count: " + n);
        int expected = HEADER_BYTES + 2 * n;
        if (length != expected)
            throw new PacketFormatException("Packet length mismatch: expected " + expected + ", got " + length);

        float[] amplitude = new float[n];
        float[] phase = new float[n];
        for (int i = 0; i < n; i++) {
            int imag = in.get();
            int real = in.get();
            amplitude[i] = (float) Math.hypot(real, imag);
            phase[i] = (float) Math.atan2(imag, real);
        }
        return new CsiFrame(sensorId, seq, timestampUs, rssi, amplitude, phase);
    }

    public static byte[] encodeForTesting(int sensorId, long seq, long timestampUs, int rssi,
                                          byte[] imagRealPairs) {
        if (sensorId < 0 || sensorId > 255 || (imagRealPairs.length & 1) != 0)
            throw new IllegalArgumentException("Invalid sensor id or imaginary/real pairs");
        int n = imagRealPairs.length / 2;
        ByteBuffer out = ByteBuffer.allocate(HEADER_BYTES + imagRealPairs.length).order(ByteOrder.BIG_ENDIAN);
        out.put(MAGIC).put((byte) sensorId).putInt((int) seq).putLong(timestampUs).put((byte) rssi)
                .putShort((short) n).put(imagRealPairs);
        return out.array();
    }

    public static final class PacketFormatException extends Exception {
        public PacketFormatException(String message) { super(message); }
    }
}
