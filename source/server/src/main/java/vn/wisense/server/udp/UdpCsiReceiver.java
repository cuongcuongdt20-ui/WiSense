package vn.wisense.server.udp;

import vn.wisense.common.model.CsiFrame;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** Receives CSI1 datagrams, decodes them and rejects duplicate/out-of-order sequence numbers. */
public final class UdpCsiReceiver implements AutoCloseable {
    private static final int MAX_PACKET_BYTES = 65_507;
    private final int port;
    private final BiConsumer<CsiFrame, PacketTracker.Snapshot> frameSink;
    private final Map<Integer, PacketTracker> trackers = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile DatagramSocket socket;
    private long malformedPackets;

    public UdpCsiReceiver(int port, BiConsumer<CsiFrame, PacketTracker.Snapshot> frameSink) {
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("Invalid UDP port: " + port);
        this.port = port;
        this.frameSink = frameSink;
    }

    public void run() throws IOException {
        try (DatagramSocket datagramSocket = new DatagramSocket(port)) {
            socket = datagramSocket;
            running = true;
            System.out.println("UDP CSI receiver listening on port " + port);
            while (running) {
                byte[] buffer = new byte[MAX_PACKET_BYTES];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    datagramSocket.receive(packet);
                    CsiFrame frame = CsiPacketDecoder.decode(packet.getData(), packet.getLength());
                    PacketTracker tracker = trackers.computeIfAbsent(frame.sensorId(), ignored -> new PacketTracker());
                    long now = System.nanoTime();
                    if (!tracker.accept(frame.seq(), now)) continue;
                    frameSink.accept(frame, tracker.snapshot(now));
                } catch (CsiPacketDecoder.PacketFormatException e) {
                    malformedPackets++;
                    if (malformedPackets <= 10 || malformedPackets % 100 == 0)
                        System.err.println("Ignoring malformed UDP packet #" + malformedPackets + ": " + e.getMessage());
                } catch (SocketException e) {
                    if (running) throw e;
                }
            }
        } finally {
            socket = null;
            running = false;
        }
    }

    public long malformedPackets() { return malformedPackets; }

    @Override public void close() {
        running = false;
        DatagramSocket current = socket;
        if (current != null) current.close();
    }
}
