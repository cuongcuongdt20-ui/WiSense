package vn.wisense.server.tcp;

import vn.wisense.common.model.Event;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** TCP server using 4-byte big-endian length + UTF-8 JSON frames. */
public final class TcpServer implements AutoCloseable {
    public static final int DEFAULT_MAX_FRAME_BYTES = 1 << 20;
    private final int port;
    private final Set<ClientSession> clients = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wisense-tcp-heartbeat"); t.setDaemon(true); return t;
    });
    private volatile ServerSocket serverSocket;

    public TcpServer(int port) {
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("Invalid TCP port: " + port);
        this.port = port;
    }

    public void run() throws IOException {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("TCP server already running");
        try (ServerSocket ss = new ServerSocket(port)) {
            serverSocket = ss;
            heartbeat.scheduleAtFixedRate(() -> broadcastRaw("{\"t\":\"PING\"}"), 5, 5, TimeUnit.SECONDS);
            System.out.println("TCP server listening on port " + port);
            while (running.get()) {
                try {
                    Socket socket = ss.accept();
                    socket.setTcpNoDelay(true);
                    ClientSession session = new ClientSession(socket, this::removeClient);
                    clients.add(session);
                    session.start();
                    System.out.println("Client connected: " + socket.getRemoteSocketAddress());
                } catch (SocketException e) {
                    if (running.get()) throw e;
                }
            }
        } finally {
            running.set(false);
            serverSocket = null;
            heartbeat.shutdownNow();
            for (ClientSession client : clients.toArray(ClientSession[]::new)) client.close();
            clients.clear();
        }
    }

    public void broadcast(Event event) {
        if (event != null) broadcastRaw(event.toJson());
    }

    public void broadcastRaw(String json) {
        for (ClientSession client : clients) {
            try { client.sendJson(json); }
            catch (IOException e) { client.close(); }
        }
    }

    private void removeClient(ClientSession client) { clients.remove(client); }
    public int clientCount() { return clients.size(); }

    @Override public void close() {
        running.set(false);
        ServerSocket current = serverSocket;
        if (current != null) {
            try { current.close(); } catch (IOException ignored) {}
        }
        for (ClientSession client : clients.toArray(ClientSession[]::new)) client.close();
        heartbeat.shutdownNow();
    }
}
