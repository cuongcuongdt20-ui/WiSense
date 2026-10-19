package vn.wisense.server.tcp;

import com.google.gson.JsonObject;
import vn.wisense.common.model.Event;
import vn.wisense.common.protocol.Msg;

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
import java.util.concurrent.atomic.AtomicLong;

/** TCP server using 4-byte big-endian length + UTF-8 JSON frames. */
public final class TcpServer implements AutoCloseable {
    public static final int DEFAULT_MAX_FRAME_BYTES = 1 << 20;
    private final int port;
    private final Set<ClientSession> clients = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger nextSession = new AtomicInteger(1);
    private final AtomicLong fallIds = new AtomicLong();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wisense-tcp-heartbeat"); t.setDaemon(true); return t;
    });
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile CommandHandler commandHandler;
    private volatile FallDelivery fallDelivery;

    public TcpServer(int port) {
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("Invalid TCP port: " + port);
        this.port = port;
    }

    public void setCommandHandler(CommandHandler handler) { this.commandHandler = handler; }
    public void setFallDelivery(FallDelivery delivery) { this.fallDelivery = delivery; }
    public List<ClientSession> sessions() { return new ArrayList<>(clients); }

    /** Binds synchronously so startup fails before UDP reception begins if the TCP port is unavailable. */
    public synchronized void start() throws IOException {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("TCP server already running");
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);
            ss.bind(new java.net.InetSocketAddress(port));
            serverSocket = ss;
        } catch (IOException e) {
            running.set(false);
            try { ss.close(); } catch (IOException ignored) {}
            throw e;
        }
        FallDelivery fd = fallDelivery;
        if (fd != null) fd.start();
        heartbeat.scheduleAtFixedRate(() -> broadcastRaw(addServerTimestamp(Msg.ping(), false)), 5, 5, TimeUnit.SECONDS);
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
                    if (running.get()) close();
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
        String json = addServerTimestamp(event.toJson(), event.type().equals("FALL"));
        if (event.type().equals("FALL")) {
            FallDelivery fd = fallDelivery;
            if (fd != null) {
                JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                long id = o.get("id").getAsLong();
                fd.onFall(event.sensorId(), id, json);
                return;
            }
        }
        broadcastEvent(event.sensorId(), event.type(), json);
    }

    public void broadcastRaw(String json) {
        for (ClientSession client : clients) {
            if (json.contains("\"t\":\"PING\"")) client.enqueueJson(json);
        }
    }

    private void broadcastEvent(int sensor, String type, String json) {
        for (ClientSession client : clients) {
            if (client.wantsEvent(sensor, type)) client.enqueueJson(json);
        }
    }

    private String addServerTimestamp(String json, boolean fall) {
        JsonObject object = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        object.addProperty("ts", System.currentTimeMillis());
        if (fall && !object.has("id")) object.addProperty("id", fallIds.incrementAndGet());
        return object.toString();
    }

    void removeClient(ClientSession client) { clients.remove(client); }
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
