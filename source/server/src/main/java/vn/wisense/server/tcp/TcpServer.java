package vn.wisense.server.tcp;

import vn.wisense.common.model.Event;
import vn.wisense.common.protocol.Msg;
import vn.wisense.server.sensor.CsiIngest;
import vn.wisense.server.sensor.SensorRegistry;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP server trung tâm, một cổng cho cả hai loại client: client giám sát (khung JSON) và client cảm biến
 * (HELLO role=sensor rồi gửi khung nhị phân CSI1). Mỗi khung là 4 byte độ dài big-endian + nội dung.
 */
public final class TcpServer implements AutoCloseable {
    public static final int DEFAULT_MAX_FRAME_BYTES = 1 << 20;
    private final int port;
    private final Set<ClientSession> clients = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger nextSession = new AtomicInteger(1);
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wisense-tcp-heartbeat"); t.setDaemon(true); return t;
    });
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile CommandHandler commandHandler;
    private volatile FallDelivery fallDelivery;
    private volatile CsiIngest ingest;
    private volatile SensorRegistry registry;

    public TcpServer(int port) {
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("Invalid TCP port: " + port);
        this.port = port;
    }

    /** Phải gọi trước {@link #start()}: mỗi phiên giữ handler từ lúc được nhận kết nối. */
    public void setCommandHandler(CommandHandler handler) { this.commandHandler = handler; }
    public void setFallDelivery(FallDelivery delivery) { this.fallDelivery = delivery; }
    /** Bật client cảm biến TCP: gói CSI1 đi vào ingest, trạng thái cảm biến ở registry. */
    public void setSensorSupport(CsiIngest ingest, SensorRegistry registry) {
        this.ingest = ingest;
        this.registry = registry;
    }
    public List<ClientSession> sessions() { return new ArrayList<>(clients); }

    /** Binds synchronously so startup fails before UDP reception begins if the TCP port is unavailable. */
    public synchronized void start() throws IOException {
        if (commandHandler == null) throw new IllegalStateException("setCommandHandler must be called before start");
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("TCP server already running");
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);
            ss.bind(new java.net.InetSocketAddress(port), 512);
            serverSocket = ss;
        } catch (IOException e) {
            running.set(false);
            try { ss.close(); } catch (IOException ignored) {}
            throw e;
        }
        FallDelivery fd = fallDelivery;
        if (fd != null) fd.start();
        heartbeat.scheduleAtFixedRate(() -> broadcastToMonitors(Msg.ping()), 5, 5, TimeUnit.SECONDS);
        acceptThread = new Thread(this::acceptLoop, "wisense-tcp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        System.out.println("TCP server listening on port " + port);
    }

    /** Compatibility entry point for callers that prefer a blocking run method. */
    public void run() throws IOException {
        start();
        try { Thread t = acceptThread; if (t != null) t.join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); close(); }
    }

    private void acceptLoop() {
        try {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    ClientSession session = new ClientSession(socket, this, commandHandler, nextSession.getAndIncrement());
                    clients.add(session);
                    session.start();
                    System.out.println("Client connected: " + socket.getRemoteSocketAddress());
                } catch (SocketException e) {
                    if (running.get()) System.err.println("TCP accept failed: " + e.getMessage());
                } catch (IOException e) {
                    if (running.get()) System.err.println("Could not create client session: " + e.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }

    public void broadcast(Event event) {
        if (event == null) return;
        String json = event.toJson();
        if ("FALL".equals(event.type())) {
            FallDelivery fd = fallDelivery;
            if (fd != null) {
                fd.onFall(event.sensorId(), event.id(), json);
                return;
            }
        }
        broadcastEvent(event.sensorId(), event.type(), json);
    }

    /** Gửi cho mọi client giám sát đã HELLO (PING, SENSOR_STATUS); không gửi cho cảm biến. */
    public void broadcastToMonitors(String json) {
        for (ClientSession client : clients) {
            if (client.isMonitor()) client.enqueueJson(json);
        }
    }

    private void broadcastEvent(int sensor, String type, String json) {
        for (ClientSession client : clients) {
            if (client.wantsEvent(sensor, type)) client.enqueueJson(json);
        }
    }

    /** Gói CSI1 từ một client cảm biến (gọi trên luồng đọc của kết nối đó). */
    void onSensorPacket(ClientSession session, byte[] packet) {
        CsiIngest in = ingest;
        if (in != null) in.accept(packet, packet.length, session.sensorId());
    }

    void removeClient(ClientSession client) {
        clients.remove(client);
        SensorRegistry reg = registry;
        if (reg != null && client.isSensor()) reg.unregister(client.sensorId(), client);
    }
    public int clientCount() { return clients.size(); }

    @Override public void close() {
        if (!running.getAndSet(false) && serverSocket == null) return;
        ServerSocket current = serverSocket;
        serverSocket = null;
        if (current != null) try { current.close(); } catch (IOException ignored) {}
        for (ClientSession client : clients.toArray(ClientSession[]::new)) client.close();
        clients.clear();
        heartbeat.shutdownNow();
        FallDelivery fd = fallDelivery;
        if (fd != null) fd.close();
    }
}
