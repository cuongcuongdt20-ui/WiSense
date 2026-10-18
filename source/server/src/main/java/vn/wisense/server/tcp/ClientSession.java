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

/** One connected client. Reads framed client messages (especially PONG) and writes framed JSON. */
public final class ClientSession implements AutoCloseable {
    private final Socket socket;
    private final Consumer<ClientSession> onClose;
    private final DataInputStream in;
    private final DataOutputStream out;
    private volatile boolean running = true;
    private final Object writeLock = new Object();

    ClientSession(Socket socket, Consumer<ClientSession> onClose) throws IOException {
        this.socket = socket;
        this.socket.setSoTimeout(15_000); // server heartbeat is every 5 seconds
        this.onClose = onClose;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    void start() {
        Thread thread = new Thread(this::readLoop, "wisense-client-" + socket.getRemoteSocketAddress());
        thread.setDaemon(true);
        thread.start();
    }

    public void sendJson(String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        if (payload.length == 0 || payload.length > TcpServer.DEFAULT_MAX_FRAME_BYTES)
            throw new IOException("Invalid outgoing JSON frame length: " + payload.length);
        synchronized (writeLock) {
            if (!running) throw new IOException("Client is closed");
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
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
                    sendJson("{\"t\":\"PONG\"}");
                }
            }
        } catch (IOException ignored) {
            // A disconnected or malformed client is removed without stopping the server.
        } finally { close(); }
    }

    @Override public void close() {
        if (!running) return;
        running = false;
        try { socket.close(); } catch (IOException ignored) {}
        onClose.accept(this);
    }
}
