package vn.wisense.server.tcp;

import vn.wisense.common.protocol.FrameCodec;
import vn.wisense.common.protocol.Msg;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One TCP client session: one reader, one writer, and a bounded outgoing queue. */
public final class ClientSession implements AutoCloseable, FallDelivery.Recipient {
    public enum State { CONNECTED, AUTHED, SUBSCRIBED, CLOSED }
    private static final int OUTGOING_QUEUE_CAPACITY = 256;
    private final Socket socket;
    private final TcpServer owner;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final BlockingQueue<String> outgoing = new ArrayBlockingQueue<>(OUTGOING_QUEUE_CAPACITY);
    private final Set<Integer> sensors = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile State state = State.CONNECTED;
    private volatile String name;
    private volatile boolean wantSignal;
    private volatile int sessionId;
    private volatile CommandHandler commandHandler;

    ClientSession(Socket socket, TcpServer owner, CommandHandler handler, int sessionId) throws IOException {
        this.socket = socket;
        this.owner = owner;
        this.commandHandler = handler;
        this.sessionId = sessionId;
        socket.setSoTimeout(15_000);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    void setCommandHandler(CommandHandler handler) { this.commandHandler = handler; }
    void start() {
        Thread writer = new Thread(this::writeLoop, "wisense-writer-" + socket.getRemoteSocketAddress());
        writer.setDaemon(true); writer.start();
        Thread reader = new Thread(this::readLoop, "wisense-reader-" + socket.getRemoteSocketAddress());
        reader.setDaemon(true); reader.start();
    }

    public boolean send(String json) { return enqueueJson(json); }
    public boolean enqueueJson(String json) {
        if (closed.get() || json == null) return false;
        if (outgoing.offer(json)) return true;
        // Drop queued SIGNAL first. If only important messages remain, disconnect this client only.
        for (String queued : outgoing) {
            if (isSignal(queued) && outgoing.remove(queued)) break;
        }
        if (outgoing.offer(json)) return true;
        close();
        return false;
    }

    private static boolean isSignal(String json) {
        return json.contains("\"t\":\"SIGNAL\"") || json.contains("\"t\" : \"SIGNAL\"");
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                String json = outgoing.poll(1, TimeUnit.SECONDS);
                if (json != null) FrameCodec.writeFrame(out, json);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (IOException ignored) { /* only this client is closed */ }
        finally { close(); }
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                String json;
                try { json = FrameCodec.readFrame(in); }
                catch (EOFException e) { break; }
                CommandHandler handler = commandHandler;
                if (handler == null) send(Msg.error(Msg.INVALID_STATE, "Server is initializing"));
                else handler.handle(this, json);
            }
        } catch (IOException ignored) { /* malformed frame or disconnected client */ }
        finally { close(); }
    }

    State state() { return state; }
    void authenticate(String clientName) {
        name = clientName;
        state = State.AUTHED;
    }
    void subscribe(Iterable<Integer> ids, boolean signal) {
        sensors.clear();
        ids.forEach(sensors::add);
        wantSignal = signal;
        state = State.SUBSCRIBED;
    }
    int sessionId() { return sessionId; }
    boolean wantsSignal() { return wantSignal; }
    boolean isSubscribed() { return state == State.SUBSCRIBED; }
    @Override public String name() { return name; }
    @Override public boolean wants(int sensor) { return isSubscribed() && sensors.contains(sensor); }
    boolean wantsEvent(int sensor, String type) {
        if (type.equals("PING")) return !closed.get();
        if (!wants(sensor)) return false;
        return !type.equals("SIGNAL") || wantSignal;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        state = State.CLOSED;
        outgoing.clear();
        try { socket.close(); } catch (IOException ignored) {}
        owner.removeClient(this);
    }
}
