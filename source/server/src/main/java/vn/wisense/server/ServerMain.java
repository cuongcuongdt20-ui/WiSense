package vn.wisense.server;

import vn.wisense.common.model.Detector;
import vn.wisense.server.sensor.EventLog;
import vn.wisense.server.sensor.SensorManager;
import vn.wisense.server.tcp.CommandHandler;
import vn.wisense.server.tcp.FallDelivery;
import vn.wisense.server.tcp.TcpServer;
import vn.wisense.server.udp.UdpCsiReceiver;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Path;

/** Application entry point for the WiSense network server (TV2). */
public final class ServerMain {
    private static final int DEFAULT_UDP_PORT = 5005;
    private static final int DEFAULT_TCP_PORT = 7000;
    private ServerMain() {}

    public static void main(String[] args) throws Exception {
        int udpPort = intArg(args, "--udp-port", DEFAULT_UDP_PORT);
        int tcpPort = intArg(args, "--tcp-port", DEFAULT_TCP_PORT);
        TcpServer tcpServer = new TcpServer(tcpPort);
        EventLog eventLog = new EventLog(Path.of("data", "events.csv"));
        SensorManager sensors = new SensorManager(event -> {
            eventLog.record(event);
            tcpServer.broadcast(event);
        });
        loadDetector(sensors, "vn.wisense.detect.motion.MotionDetector");
        loadDetector(sensors, "vn.wisense.detect.breathing.BreathingEstimator");
        loadDetector(sensors, "vn.wisense.detect.fall.FallDetector");

        FallDelivery fallDelivery = new FallDelivery(tcpServer::sessions);
        tcpServer.setFallDelivery(fallDelivery);
        tcpServer.setCommandHandler(new CommandHandler(
                sensors::sensorIds,
                eventLog::recent,
                sensors::configure,
                fallDelivery));

        UdpCsiReceiver udpReceiver = new UdpCsiReceiver(udpPort,
                (frame, stats) -> sensors.onFrame(frame, stats));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            udpReceiver.close();
            tcpServer.close();
            sensors.close();
        }, "wisense-shutdown"));

        // Bind TCP synchronously first. If the port is unavailable, fail instead of silently
        // leaving UDP running with no client/server connection.
        try {
            tcpServer.start();
        } catch (IOException e) {
            sensors.close();
            throw new IOException("Cannot start TCP server on port " + tcpPort + ": " + e.getMessage(), e);
        }
        try {
            udpReceiver.run();
        } finally {
            udpReceiver.close();
            tcpServer.close();
            sensors.close();
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
}
