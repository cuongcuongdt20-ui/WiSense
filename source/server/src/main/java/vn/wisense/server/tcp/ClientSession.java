package vn.wisense.server.tcp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import vn.wisense.common.protocol.FrameCodec;
import vn.wisense.common.protocol.Msg;
import vn.wisense.server.auth.Access;
import vn.wisense.server.auth.Authenticator;
import vn.wisense.server.sensor.SensorRegistry;

/**
 * Một kết nối TCP: một luồng đọc, một luồng ghi và một hàng đợi gửi có giới hạn.
 * Sau HELLO, phiên là client giám sát (MONITOR) hoặc client cảm biến (SENSOR).
 * Cảm biến được gửi khung nhị phân CSI1; người giám sát chỉ được gửi khung JSON.
 */
public final class ClientSession implements AutoCloseable, FallDelivery.Recipient, SensorRegistry.Link {
    /** CHALLENGED: đã HELLO, đang chờ AUTH (chỉ khi server bật xác thực). */
    public enum State { CONNECTED, CHALLENGED, AUTHED, SUBSCRIBED, CLOSED }
    public enum Role { UNKNOWN, MONITOR, SENSOR }
    /** HELLO đã nhận, chờ AUTH: vai trò, tên hoặc mã cảm biến đã khai, và CHALLENGE đã gửi. */
    record PendingAuth(Role role, String name, int sensor, Authenticator.Challenge challenge) {}
    private static final int OUTGOING_QUEUE_CAPACITY = 256;
    /** Tin đánh dấu cuối hàng đợi: luồng ghi gặp nó thì đóng kết nối (đóng sau khi đã gửi hết). */
    private static final String CLOSE_MARK = new String("<close>");
    private final Socket socket;
    private final TcpServer owner;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final BlockingQueue<String> outgoing = new ArrayBlockingQueue<>(OUTGOING_QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CommandHandler commandHandler;
    private final int sessionId;
    private volatile Set<Integer> sensors = Set.of();
    private volatile State state = State.CONNECTED;
    private volatile Role role = Role.UNKNOWN;
    private volatile String name;
    private volatile boolean wantSignal;
    private volatile int sensorId = -1;
    private volatile Access access = Access.ALL;
    private volatile PendingAuth pending;

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

    void start() {
        Thread writer = new Thread(this::writeLoop, "wisense-writer-" + socket.getRemoteSocketAddress());
        writer.setDaemon(true); writer.start();
        Thread reader = new Thread(this::readLoop, "wisense-reader-" + socket.getRemoteSocketAddress());
        reader.setDaemon(true); reader.start();
    }

    @Override public boolean send(String json) { return enqueueJson(json); }
    public boolean enqueueJson(String json) {
        if (closed.get() || closing.get() || json == null) return false;
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

    /**
     * Gửi nốt các tin đã xếp (ví dụ ERROR vừa gửi) rồi mới đóng; dùng khi từ chối kết nối hoặc khi client chào BYE.
     * {@link #close()} thì đóng ngay và bỏ các tin còn trong hàng đợi.
     */
    void closeAfterFlush() {
        if (closed.get() || !closing.compareAndSet(false, true)) return;
        while (!outgoing.offer(CLOSE_MARK)) outgoing.poll();   // hàng đợi đầy: bỏ tin cũ nhất để chắc chắn đóng được
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                String json = outgoing.poll(1, TimeUnit.SECONDS);
                if (json == CLOSE_MARK) break;
                if (json != null) FrameCodec.writeFrame(out, json);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (IOException ignored) { /* only this client is closed */ }
        finally { close(); }
    }

    private void readLoop() {
        try {
            while (!closed.get() && !closing.get()) {
                byte[] frame;
                try { frame = FrameCodec.readFrameBytes(in); }
                catch (EOFException e) { break; }
                if (closing.get()) break;                        // đang đóng: không xử lí lệnh mới
                if (FrameCodec.isJson(frame)) {
                    commandHandler.handle(this, new String(frame, StandardCharsets.UTF_8));
                } else if (role == Role.SENSOR) {
                    owner.onSensorPacket(this, frame);           // gói CSI1 nhị phân của cảm biến
                } else {
                    send(Msg.error(Msg.BAD_JSON, "Chi client cam bien (sau HELLO role=sensor) duoc gui khung nhi phan"));
                }
            }
        } catch (IOException ignored) { /* malformed frame or disconnected client */ }
        finally {
            if (!closing.get()) close();                         // đang closeAfterFlush: để luồng ghi gửi nốt rồi đóng
        }
    }

    State state() { return state; }
    Role role() { return role; }
    boolean isSensor() { return role == Role.SENSOR; }
    int sensorId() { return sensorId; }

    /** Địa chỉ IP của client, dùng để đếm số lần đăng nhập sai. */
    String remoteAddress() { return socket.getInetAddress().getHostAddress(); }

    /** Đã gửi CHALLENGE, chờ AUTH. */
    void challenge(PendingAuth p) {
        pending = p;
        state = State.CHALLENGED;
    }

    /** Lấy và xoá lần bắt tay đang chờ: mỗi CHALLENGE chỉ được trả lời một lần. */
    PendingAuth takePending() {
        PendingAuth p = pending;
        pending = null;
        return p;
    }

    /** Client giám sát đã đăng nhập (hoặc HELLO khi server không bật xác thực). */
    void authenticate(String clientName, Access rights) {
        name = clientName;
        access = rights;
        role = Role.MONITOR;
        state = State.AUTHED;
    }

    Access access() { return access; }

    /** Server chứa phiên này (lệnh quản trị cần danh sách mọi phiên). */
    TcpServer server() { return owner; }

    /** Admin ngắt phiên này: báo lí do rồi đóng sau khi gửi xong. */
    void kick(String reason) {
        send(Msg.error(Msg.KICKED, reason));
        closeAfterFlush();
    }

    /** HELLO của client cảm biến (đã được SensorRegistry chấp nhận mã này). */
    void authenticateSensor(int id) {
        sensorId = id;
        name = "sensor-" + id;
        role = Role.SENSOR;
        state = State.AUTHED;
    }

    void subscribe(Iterable<Integer> ids, boolean signal) {
        Set<Integer> next = ConcurrentHashMap.newKeySet();
        ids.forEach(next::add);
        sensors = next;                                         // gán một lần: broadcast không thấy tập rỗng giữa chừng
        wantSignal = signal;
        state = State.SUBSCRIBED;
    }

    int sessionId() { return sessionId; }
    boolean wantsSignal() { return wantSignal; }
    boolean isSubscribed() { return state == State.SUBSCRIBED; }
    /** Client giám sát đã HELLO: được nhận PING và SENSOR_STATUS. */
    boolean isMonitor() { return role == Role.MONITOR && !closed.get(); }
    /** Client giám sát được xem cảm biến này: nhận SENSOR_STATUS của nó. */
    boolean canSee(int sensor) { return isMonitor() && access.canSee(sensor); }
    @Override public String name() { return name; }
    @Override public boolean wants(int sensor) { return isSubscribed() && sensors.contains(sensor); }
    @Override public boolean isOpen() { return !closed.get(); }
    boolean wantsEvent(int sensor, String type) {
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
