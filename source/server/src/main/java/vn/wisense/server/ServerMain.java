package vn.wisense.server;

import vn.wisense.common.model.Detector;
import vn.wisense.server.sensor.SensorManager;
import vn.wisense.server.tcp.TcpServer;
import vn.wisense.server.udp.UdpCsiReceiver;

import java.io.IOException;
import java.lang.reflect.Constructor;

/** Application entry point for the WiSense network server (TV2). */
public final class ServerMain {
    private static final int DEFAULT_UDP_PORT = 5005;
    private static final int DEFAULT_TCP_PORT = 7000;

    private ServerMain() {}

    public static void main(String[] args) throws Exception {
        int udpPort = intArg(args, "--udp-port", DEFAULT_UDP_PORT);
        int tcpPort = intArg(args, "--tcp-port", DEFAULT_TCP_PORT);
        TcpServer tcpServer = new TcpServer(tcpPort);
        SensorManager sensors = new SensorManager(tcpServer::broadcast);
        loadDetector(sensors, "vn.wisense.detect.motion.MotionDetector");
        loadDetector(sensors, "vn.wisense.detect.breathing.BreathingEstimator");
        loadDetector(sensors, "vn.wisense.detect.fall.FallDetector");

        UdpCsiReceiver udpReceiver = new UdpCsiReceiver(udpPort,
                (frame, stats) -> sensors.onFrame(frame, stats));
        Thread tcpThread = new Thread(() -> {
            try { tcpServer.run(); }
            catch (IOException e) { if (!Thread.currentThread().isInterrupted())
                System.err.println("TCP server stopped: " + e.getMessage()); }
        }, "wisense-tcp-server");
        tcpThread.setDaemon(true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            udpReceiver.close();
            tcpServer.close();
            sensors.close();
        }, "wisense-shutdown"));
        tcpThread.start();
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
        for (String arg : args) {
            if (arg.startsWith(key + "=")) return Integer.parseInt(arg.substring(key.length() + 1));
        }
        return fallback;
    }
}
