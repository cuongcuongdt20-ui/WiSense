package vn.wisense.server;

import vn.wisense.common.model.Detector;
import vn.wisense.server.auth.Authenticator;
import vn.wisense.server.sensor.CsiIngest;
import vn.wisense.server.sensor.EventLog;
import vn.wisense.server.sensor.SensorManager;
import vn.wisense.server.sensor.SensorRegistry;
import vn.wisense.server.tcp.CommandHandler;
import vn.wisense.server.tcp.FallDelivery;
import vn.wisense.server.tcp.TcpServer;
import vn.wisense.server.udp.UdpCsiReceiver;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Điểm bắt đầu của server WiSense. Một TCP server (mặc định cổng 7000) phục vụ cả client cảm biến lẫn
 * client giám sát; UDP (mặc định 5005) chỉ là kênh dự phòng cho dữ liệu cảm biến.
 *
 * <pre>
 * --tcp-port=7000      cổng TCP cho mọi client
 * --udp-port=5005      cổng UDP dự phòng; 0 = tắt
 * --record=file.csv    ghi mọi gói CSI nhận được ra CSV (phát lại bằng ReplayMain --file)
 * --event-log=file.csv lịch sử sự kiện (mặc định data/events.csv)
 * --users=file.csv     tài khoản người giám sát (mặc định data/users.csv, tạo bằng UserAdmin)
 * --sensor-keys=file   khoá bí mật của từng cảm biến (mặc định data/sensors.csv)
 * --no-auth            tắt xác thực: ai nối vào cũng được toàn quyền (chỉ để chạy thử)
 * </pre>
 * UDP không có bước đăng nhập; khi cần chặn cảm biến giả hãy tắt UDP bằng --udp-port=0.
 */
public final class ServerMain {
    private static final int DEFAULT_UDP_PORT = 5005;
    private static final int DEFAULT_TCP_PORT = 7000;
    private ServerMain() {}

    public static void main(String[] args) throws Exception {
        int udpPort = intArg(args, "--udp-port", DEFAULT_UDP_PORT);
        int tcpPort = intArg(args, "--tcp-port", DEFAULT_TCP_PORT);
        String record = stringArg(args, "--record", "");
        Path eventLogPath = Path.of(stringArg(args, "--event-log", "data/events.csv"));
        boolean noAuth = java.util.Arrays.asList(args).contains("--no-auth");
        Authenticator authenticator = null;
        if (noAuth) {
            System.out.println("CANH BAO: --no-auth, moi may trong mang deu xem va dieu khien duoc");
        } else {
            authenticator = Authenticator.load(Path.of(stringArg(args, "--users", "data/users.csv")),
                    Path.of(stringArg(args, "--sensor-keys", "data/sensors.csv")));
        }

        TcpServer tcpServer = new TcpServer(tcpPort);
        EventLog eventLog = new EventLog(eventLogPath);
        SensorManager sensors = new SensorManager(event -> {
            try {
                eventLog.record(event);
            } catch (RuntimeException e) {                  // lỗi ghi lịch sử không được làm mất cảnh báo
                System.err.println("Could not record event: " + e.getMessage());
            }
            tcpServer.broadcast(event);
        });
        loadDetector(sensors, "vn.wisense.detect.motion.MotionDetector");
        loadDetector(sensors, "vn.wisense.detect.breathing.BreathingEstimator");
        loadDetector(sensors, "vn.wisense.detect.fall.FallDetector");

        SensorRegistry registry = new SensorRegistry(tcpServer::broadcastSensorStatus);
        CsiIngest ingest = new CsiIngest(sensors::onFrame, registry::touch, record.isBlank() ? null : Path.of(record));
        FallDelivery fallDelivery = new FallDelivery(tcpServer::sessions);
        tcpServer.setFallDelivery(fallDelivery);
        tcpServer.setSensorSupport(ingest, registry);
        tcpServer.setCommandHandler(new CommandHandler(
                registry::known,
                eventLog::recent,
                sensors::configure,
                fallDelivery,
                registry,
                authenticator));

        UdpCsiReceiver udpReceiver = udpPort > 0 ? new UdpCsiReceiver(udpPort, ingest) : null;
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (udpReceiver != null) udpReceiver.close();
            tcpServer.close();
            registry.close();
            sensors.close();
            ingest.close();                                 // đóng file --record để không mất phần cuối
            stopped.countDown();
        }, "wisense-shutdown"));

        // Mở cổng TCP trước; không mở được thì dừng hẳn thay vì chạy UDP mà không có client nào nối được.
        try {
            tcpServer.start();
        } catch (IOException e) {
            sensors.close();
            ingest.close();
            throw new IOException("Cannot start TCP server on port " + tcpPort + ": " + e.getMessage(), e);
        }
        registry.start();
        if (!record.isBlank()) System.out.println("Ghi du lieu CSI vao " + record);

        if (udpReceiver != null) {
            udpReceiver.run();                              // chạy tới khi tắt server
        } else {
            stopped.await();
        }
    }

    private static void loadDetector(SensorManager manager, String className) {
        try {
            Class<?> type = Class.forName(className);
            if (!Detector.class.isAssignableFrom(type)) {
                System.err.println("Ignoring detector that does not implement Detector: " + className);
                return;
            }
            Constructor<?> constructor = type.getDeclaredConstructor();
            Detector detector = (Detector) constructor.newInstance();
            manager.addDetector(detector);
            System.out.println("Loaded detector: " + className);
        } catch (ClassNotFoundException e) {
            System.out.println("Detector not present yet (will be available after integration): " + className);
        } catch (ReflectiveOperationException e) {
            System.err.println("Could not load detector " + className + ": " + e.getMessage());
        }
    }

    private static int intArg(String[] args, String key, int fallback) {
        for (String arg : args) if (arg.startsWith(key + "=")) return Integer.parseInt(arg.substring(key.length() + 1));
        return fallback;
    }

    private static String stringArg(String[] args, String key, String fallback) {
        for (String arg : args) if (arg.startsWith(key + "=")) return arg.substring(key.length() + 1);
        return fallback;
    }
}
