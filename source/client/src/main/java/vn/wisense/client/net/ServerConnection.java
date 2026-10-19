package vn.wisense.client.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * Kết nối TCP tới server trên một luồng nền: đọc khung "4 byte độ dài + JSON",
 * trả PONG cho PING, rớt thì tự nối lại sau 2 giây. Tin nhận được chỉ được bỏ vào hàng đợi.
 */
public class ServerConnection implements Runnable {
    private static final int MAX_FRAME = 1 << 20;          // 1 MB, chặn độ dài rác
    private final String host;
    private final int port;
    private final Queue<Message> inbox;
    private final Consumer<Boolean> onStatus;               // gọi từ luồng nền
    private volatile boolean running = true;
    private volatile Socket socket;
    private volatile DataOutputStream out;

    public ServerConnection(String host, int port, Queue<Message> inbox, Consumer<Boolean> onStatus) {
        this.host = host;
        this.port = port;
        this.inbox = inbox;
        this.onStatus = onStatus;
    }

    public void start() {
        Thread t = new Thread(this, "server-connection");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void run() {
        while (running) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 3000);
                s.setSoTimeout(15_000);                     // 15 giây không có tin nào thì coi là rớt
                socket = s;
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                onStatus.accept(true);
                while (running) {
                    int len = in.readInt();
                    if (len <= 0 || len > MAX_FRAME) throw new IOException("Do dai khung sai: " + len);
                    byte[] buf = new byte[len];
                    in.readFully(buf);
                    handle(new String(buf, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                // server tắt, mạng rớt hoặc hết thời gian chờ: rơi xuống và nối lại
            }
            onStatus.accept(false);
            if (running) sleep(2000);
        }
    }

    private void handle(String json) throws IOException {
        Message m;
        try {
            m = Message.parse(json);
        } catch (RuntimeException e) {
            System.err.println("Bo tin hong: " + json);   // khung đã đọc đủ nên luồng không lệch
            return;
        }
        if (m instanceof Message.Ping) send("{\"t\":\"PONG\"}");
        else if (m != null) inbox.offer(m);
    }

    public synchronized void send(String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
        out.flush();
    }

    public void stop() {
        running = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();                                  // làm readInt ném lỗi, luồng thoát ra
            } catch (IOException ignored) {
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
