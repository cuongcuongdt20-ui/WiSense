package vn.wisense.server.sensor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.protocol.CsiCsv;
import vn.wisense.server.udp.CsiPacketDecoder;
import vn.wisense.server.udp.PacketTracker;

/**
 * Điểm vào chung của gói CSI1, dù đến qua TCP (client cảm biến) hay UDP (dự phòng):
 * giải mã, bỏ gói trùng/sai thứ tự theo seq, ghi CSV nếu bật --record, báo SensorRegistry là cảm biến còn sống,
 * rồi đưa khung cho SensorManager. Nhiều luồng đọc (mỗi cảm biến TCP một luồng) gọi cùng lúc được.
 */
public final class CsiIngest implements AutoCloseable {
    private final BiConsumer<CsiFrame, PacketTracker.Snapshot> sink;
    private final IntConsumer onFrame;
    private final Map<Integer, PacketTracker> trackers = new ConcurrentHashMap<>();
    private final AtomicLong malformed = new AtomicLong();
    private final BufferedWriter recorder;
    private boolean recorderHeader;

    /**
     * @param sink    nơi nhận khung đã giải mã (SensorManager::onFrame)
     * @param onFrame báo có khung từ cảm biến id (SensorRegistry::touch)
     * @param record  file CSV để ghi dữ liệu thô, null nếu không ghi
     */
    public CsiIngest(BiConsumer<CsiFrame, PacketTracker.Snapshot> sink, IntConsumer onFrame, Path record) throws IOException {
        this.sink = sink;
        this.onFrame = onFrame;
        if (record == null) {
            recorder = null;
        } else {
            if (record.getParent() != null) Files.createDirectories(record.getParent());
            recorder = Files.newBufferedWriter(record, StandardCharsets.UTF_8);
            recorder.write("# WiSense CSI, ghi tai server luc " + LocalDateTime.now() + "\n");
        }
    }

    /**
     * Nhận một gói. expectedSensor &gt;= 0 thì gói phải đúng mã cảm biến đã HELLO trên kết nối đó.
     * Trả false nếu gói hỏng, sai mã cảm biến, hoặc bị bỏ vì trùng/sai thứ tự.
     */
    public boolean accept(byte[] packet, int length, int expectedSensor) {
        CsiFrame frame;
        try {
            frame = CsiPacketDecoder.decode(packet, length);
        } catch (CsiPacketDecoder.PacketFormatException e) {
            logMalformed(e.getMessage());
            return false;
        }
        if (expectedSensor >= 0 && frame.sensorId() != expectedSensor) {
            logMalformed("goi cua cam bien " + frame.sensorId() + " tren ket noi cua cam bien " + expectedSensor);
            return false;
        }
        PacketTracker tracker = trackers.computeIfAbsent(frame.sensorId(), ignored -> new PacketTracker());
        long now = System.nanoTime();
        if (!tracker.accept(frame.seq(), now)) return false;
        record(packet, length);
        onFrame.accept(frame.sensorId());
        sink.accept(frame, tracker.snapshot(now));
        return true;
    }

    public long malformedPackets() {
        return malformed.get();
    }

    private void logMalformed(String why) {
        long n = malformed.incrementAndGet();
        if (n <= 10 || n % 100 == 0) System.err.println("Bo goi CSI hong #" + n + ": " + why);
    }

    private void record(byte[] packet, int length) {
        if (recorder == null) return;
        synchronized (recorder) {
            try {
                String row = CsiCsv.toRow(packet, length);
                if (!recorderHeader) {
                    recorder.write(CsiCsv.header(CsiCsv.subcarriers(row)));
                    recorderHeader = true;
                }
                recorder.write(row);
                recorder.write('\n');
            } catch (IOException e) {
                System.err.println("Khong ghi duoc file du lieu: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        if (recorder == null) return;
        synchronized (recorder) {
            try {
                recorder.close();
            } catch (IOException ignored) {
            }
        }
    }
}
