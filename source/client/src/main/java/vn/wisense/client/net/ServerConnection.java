package vn.wisense.client.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import vn.wisense.common.auth.Credentials;
import vn.wisense.common.protocol.FrameCodec;
import vn.wisense.common.protocol.Msg;

/**
 * Kết nối TCP tới server trên một luồng nền: gửi HELLO, nhận WELCOME thì SUBSCRIBE,
 * trả PONG cho PING, trả ACK cho FALL và bỏ FALL trùng id, rớt thì tự nối lại sau 2 giây.
 * Tin nhận được chỉ được bỏ vào hàng đợi; giao diện gửi lệnh qua {@link #request}.
 *
 * <p>Server bật xác thực thì HELLO được trả bằng CHALLENGE: client trả AUTH với proof từ {@link Credentials}.
 * Bị AUTH_FAILED hoặc TOO_MANY_ATTEMPTS thì dừng hẳn, không tự nối lại, để giao diện quay về màn đăng nhập.
 */
public class ServerConnection implements Runnable {
    private static final int SEEN_FALLS = 1000;            // số id FALL gần nhất nhớ để lọc trùng
    private final String host;
    private final int port;
    private final String name;                             // giữ nguyên qua các lần nối lại để server gửi lại FALL
    private final Queue<Message> inbox;
    private volatile Consumer<Boolean> onStatus;            // gọi từ luồng nền
    private final Credentials credentials;                  // null: server không bật xác thực
    private final Set<Long> seenFalls = new LinkedHashSet<>();  // chỉ luồng nền dùng
    private volatile boolean running = true;
    private volatile Socket socket;
    private volatile DataOutputStream out;
    private volatile List<Integer> sensors;                 // null: đăng ký mọi sensor trong WELCOME

    public ServerConnection(String host, int port, String name, Queue<Message> inbox, Consumer<Boolean> onStatus) {
        this(host, port, name, null, inbox, onStatus);
    }

    public ServerConnection(String host, int port, String name, Credentials credentials, Queue<Message> inbox,
                            Consumer<Boolean> onStatus) {
        this.host = host;
        this.port = port;
        this.name = name;
        this.credentials = credentials;
        this.inbox = inbox;
        this.onStatus = onStatus;
    }

    /** Đổi nơi nhận trạng thái kết nối (màn đăng nhập giao kết nối cho màn hình chính). */
    public void setOnStatus(Consumer<Boolean> onStatus) {
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
                send(Msg.hello(name, credentials == null ? null : credentials.user()));   // SUBSCRIBE gửi khi nhận WELCOME
                while (running) handle(FrameCodec.readFrame(in));
            } catch (IOException e) {
                // server tắt, mạng rớt hoặc hết thời gian chờ: rơi xuống và nối lại
            }
            out = null;
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
        switch (m) {
            case null -> { }
            case Message.Ping p -> send(Msg.pong());
            case Message.Challenge c -> answer(c);
            case Message.ServerError e when Msg.AUTH_FAILED.equals(e.code())
                    || Msg.TOO_MANY_ATTEMPTS.equals(e.code()) || Msg.KICKED.equals(e.code()) -> {
                running = false;                            // nối lại cũng sai: dừng, server sẽ đóng kết nối
                inbox.offer(e);
            }
            case Message.Welcome w -> {
                List<Integer> wanted = sensors;
                send(Msg.subscribe(wanted != null ? wanted : w.sensors(), true));
                inbox.offer(w);
            }
            case Message.Fall f when f.id() >= 0 -> {
                send(Msg.ack(f.id()));                      // ACK cả bản gửi lại, vì ACK trước có thể đã mất
                if (seenFalls.add(f.id())) {
                    if (seenFalls.size() > SEEN_FALLS) {
                        Iterator<Long> it = seenFalls.iterator();
                        it.next();
                        it.remove();
                    }
                    inbox.offer(f);
                }
            }
            default -> inbox.offer(m);
        }
    }

    /** Trả lời CHALLENGE; không có thông tin đăng nhập thì báo AUTH_FAILED và dừng. */
    private void answer(Message.Challenge c) throws IOException {
        String proof = null;
        if (credentials != null) {
            try {
                proof = credentials.answer(c.nonce(), c.salt(), c.iterations());
            } catch (RuntimeException e) {                  // mật khẩu đã xoá mà server đổi salt
                proof = null;
            }
        }
        if (proof == null) {
            running = false;
            inbox.offer(new Message.ServerError(Msg.AUTH_FAILED, "Server yêu cầu đăng nhập lại"));
            Socket s = socket;
            if (s != null) s.close();
            return;
        }
        send(Msg.auth(proof));
    }

    /** Đổi các sensor muốn nhận; đang kết nối thì gửi SUBSCRIBE ngay, chưa thì gửi sau WELCOME. */
    public void setSensors(List<Integer> wanted) {
        sensors = List.copyOf(wanted);
        if (out != null) request(Msg.subscribe(sensors, true));
    }

    /** Gửi một lệnh (GET_HISTORY, SET_CONFIG, ...); lỗi được báo qua hàng đợi như tin ERROR. */
    public void request(String json) {
        try {
            send(json);
        } catch (IOException e) {
            inbox.offer(new Message.ServerError("NOT_CONNECTED", "Chưa kết nối được server"));
        }
    }

    /** Luồng nền gửi PONG, ACK còn luồng giao diện gửi lệnh nên phải khoá. */
    private synchronized void send(String json) throws IOException {
        DataOutputStream o = out;
        if (o == null) throw new IOException("Chua ket noi");
        FrameCodec.writeFrame(o, json);
    }

    public void stop() {
        running = false;
        try {
            send(Msg.bye());
        } catch (IOException ignored) {
            // đã rớt thì không cần chào
        }
        Socket s = socket;
        if (s != null) {
            try {
                s.close();                                  // làm readFrame ném lỗi, luồng thoát ra
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
