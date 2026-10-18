package vn.wisense.server.tcp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** One connected client. Reads framed client messages (especially PONG) and writes framed JSON. */
public final class ClientSession implements AutoCloseable {
    private final Socket socket;
    private final Consumer<ClientSession> onClose;
    private final DataInputStream in;
    private final DataOutputStream out;
    private volatile boolean running = true;
    private static final int OUTGOING_QUEUE_CAPACITY = 256;
    private final BlockingQueue<String> outgoing = new ArrayBlockingQueue<>(OUTGOING_QUEUE_CAPACITY);

    ClientSession(Socket socket, Consumer<ClientSession> onClose) throws IOException {
        this.socket = socket;
        this.socket.setSoTimeout(15_000); // server heartbeat is every 5 seconds
        this.onClose = onClose;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    void start() {
        Thread writer = new Thread(this::writeLoop, "wisense-writer-" + socket.getRemoteSocketAddress());
        writer.setDaemon(true);
        writer.start();
        Thread reader = new Thread(this::readLoop, "wisense-reader-" + socket.getRemoteSocketAddress());
        reader.setDaemon(true);
        reader.start();
    }

    /** Enqueues quickly; socket writes happen only on this client's writer thread. */
    public boolean enqueueJson(String json) {
        if (!running || json == null) return false;
        if (outgoing.offer(json)) return true;

        // SIGNAL is the least critical/highest-volume message: discard one queued
        // SIGNAL before sacrificing a MOTION/BREATH/FALL/STATS event.
        for (String queued : outgoing) {
            if (queued.contains("\"t\":\"SIGNAL\"")) {
                if (outgoing.remove(queued)) break;
            }
        }
        if (outgoing.offer(json)) return true;
        // Queue is still full of important messages. Disconnect this slow client
        // instead of allowing it to stall the UDP/detector pipeline.
        close();
        return false;
    }

    private void writeLoop() {
        try {
            while (running) {
                String json = outgoing.poll(1, TimeUnit.SECONDS);
                if (json == null) continue;
                byte[] payload = json.getBytes(StandardCharsets.UTF_8);
                if (payload.length == 0 || payload.length > TcpServer.DEFAULT_MAX_FRAME_BYTES)
                    throw new IOException("Invalid outgoing JSON frame length: " + payload.length);
                out.writeInt(payload.length);
                out.write(payload);
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // A disconnected or blocked client must not affect other clients.
        } finally {
            close();
        }
    }

    private void readLoop() {
        try {
            while (running) {
                int length;
                try { length = in.readInt(); }
                catch (EOFException e) { break; }
                if (length <= 0 || length > TcpServer.DEFAULT_MAX_FRAME_BYTES)
                    throw new IOException("Invalid incoming frame length: " + length);
                byte[] payload = new byte[length];
                in.readFully(payload);
                String json = new String(payload, StandardCharsets.UTF_8);
                // The current client only needs PING/PONG. Ignore other inbound messages safely.
                if (json.contains("\"t\"") && json.contains("\"PING\"")) {
                    // PING is normally server-to-client; echoing PONG for a PING from a client is harmless.
                    enqueueJson("{\"t\":\"PONG\"}");
                }
            }
        } catch (IOException ignored) {
            // A disconnected or malformed client is removed without stopping the server.
        } finally { close(); }
    }

    @Override public void close() {
        if (!running) return;
        running = false;
        outgoing.clear();
        try { socket.close(); } catch (IOException ignored) {}
        onClose.accept(this);
    }
}
