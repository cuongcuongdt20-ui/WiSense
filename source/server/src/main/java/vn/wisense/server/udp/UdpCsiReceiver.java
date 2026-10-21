package vn.wisense.server.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import vn.wisense.server.sensor.CsiIngest;

/**
 * Kênh dự phòng: nhận gói CSI1 qua UDP (firmware chọn giao thức UDP, hoặc ReplayMain/RecordMain kiểu cũ).
 * Mọi xử lí sau khi nhận (giải mã, theo dõi seq, ghi CSV, đưa vào SensorManager) do CsiIngest làm,
 * giống hệt đường TCP.
 */
public final class UdpCsiReceiver implements AutoCloseable {
    private static final int MAX_PACKET_BYTES = 65_507;
    private final int port;
    private final CsiIngest ingest;
    private volatile boolean running;
    private volatile DatagramSocket socket;

    public UdpCsiReceiver(int port, CsiIngest ingest) {
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("Invalid UDP port: " + port);
        this.port = port;
        this.ingest = ingest;
    }

    public void run() throws IOException {
        try (DatagramSocket datagramSocket = new DatagramSocket(port)) {
            socket = datagramSocket;
            running = true;
            System.out.println("UDP CSI receiver (du phong) listening on port " + port);
            byte[] buffer = new byte[MAX_PACKET_BYTES];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while (running) {
                try {
                    packet.setLength(buffer.length);
                    datagramSocket.receive(packet);
                    ingest.accept(packet.getData(), packet.getLength(), -1);
                } catch (SocketException e) {
                    if (running) throw e;
                }
            }
        } finally {
            socket = null;
            running = false;
        }
    }

    @Override public void close() {
        running = false;
        DatagramSocket current = socket;
        if (current != null) current.close();
    }
}
