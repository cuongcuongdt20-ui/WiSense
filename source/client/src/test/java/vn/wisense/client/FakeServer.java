package vn.wisense.client;

import com.google.gson.JsonObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import vn.wisense.common.protocol.FrameCodec;
import vn.wisense.common.protocol.Msg;

/**
 * Server giả để chạy thử client khi chưa có server thật: trả WELCOME/OK/HISTORY/ERROR cho lệnh,
 * phát đủ 5 loại tin cho cảm biến đã đăng ký, té ngã có id mỗi 30 giây và gửi lại tới khi có ACK.
 */
public class FakeServer {
    private static volatile int subscribed = 1;
    private static volatile long waitingAck = -1;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7000;
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("FakeServer nghe cong " + port);
            while (true) {
                try (Socket s = ss.accept()) {
                    System.out.println("Client noi tu " + s.getRemoteSocketAddress());
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    Thread reader = new Thread(() -> readLoop(in, out));
                    reader.setDaemon(true);
                    reader.start();
                    long fallId = 0;
                    for (int i = 0; ; i++) {                    // 20 tin SIGNAL mỗi giây
                        int sensor = subscribed;
                        double t = i / 20.0;
                        double amp = 20 + sensor + 0.5 * Math.sin(2 * Math.PI * 0.25 * t);
                        send(out, "{\"t\":\"SIGNAL\",\"sensor\":" + sensor + ",\"amp\":[" + amp + "," + amp + "]}");
                        if (i % 20 == 0) {
                            send(out, "{\"t\":\"MOTION\",\"sensor\":" + sensor + ",\"level\":0.73,\"moving\":true}");
                            send(out, "{\"t\":\"BREATH\",\"sensor\":" + sensor + ",\"bpm\":15.2,\"snr\":4.1}");
                            send(out, "{\"t\":\"STATS\",\"sensor\":" + sensor + ",\"pps\":98,\"loss\":0.02}");
                        }
                        if (i % 100 == 0) send(out, Msg.ping());
                        if (i % 600 == 300) waitingAck = ++fallId;
                        if (waitingAck > 0 && i % 60 == 0) {     // gửi lại mỗi 3 giây tới khi có ACK
                            send(out, "{\"t\":\"FALL\",\"sensor\":" + sensor + ",\"time\":"
                                    + System.currentTimeMillis() / 1000 + ",\"confidence\":0.8,\"id\":" + waitingAck + "}");
                        }
                        Thread.sleep(50);
                    }
                } catch (IOException e) {
                    System.out.println("Client ngat: " + e.getMessage());
                }
            }
        }
    }

    private static void readLoop(DataInputStream in, DataOutputStream out) {
        try {
            while (true) {
                String json = FrameCodec.readFrame(in);
                JsonObject o = Msg.parse(json);
                if (o == null) {
                    send(out, Msg.error(Msg.BAD_JSON, "Khong doc duoc: " + json));
                    continue;
                }
                System.out.println("<- " + json);
                switch (Msg.type(o)) {
                    case Msg.HELLO -> send(out, Msg.welcome(1, List.of(1, 2)));
                    case Msg.SUBSCRIBE -> {
                        subscribed = o.getAsJsonArray("sensors").get(0).getAsInt();
                        send(out, Msg.ok(Msg.SUBSCRIBE));
                    }
                    case Msg.GET_HISTORY -> send(out, Msg.history(o.get("sensor").getAsInt(), List.of(
                            "{\"t\":\"FALL\",\"sensor\":1,\"time\":1739000000,\"confidence\":0.8,\"id\":1}",
                            "{\"t\":\"BREATH\",\"sensor\":1,\"bpm\":15.2,\"snr\":4.1}")));
                    case Msg.ACK -> {
                        if (o.get("id").getAsLong() == waitingAck) waitingAck = -1;
                    }
                    case Msg.PONG, Msg.BYE -> { }
                    default -> send(out, Msg.error(Msg.UNKNOWN_TYPE, Msg.type(o)));
                }
            }
        } catch (IOException e) {
            // client đóng: vòng ghi bên ngoài sẽ ném lỗi và chờ client mới
        }
    }

    static synchronized void send(DataOutputStream out, String json) throws IOException {
        FrameCodec.writeFrame(out, json);                   // hai luồng cùng ghi nên khoá
    }
}
