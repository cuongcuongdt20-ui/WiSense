package vn.wisense.client;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Server giả để chạy thử client khi chưa có server thật: phát đủ 5 loại tin, té ngã mỗi 30 giây. */
public class FakeServer {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7000;
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("FakeServer nghe cong " + port);
            while (true) {
                try (Socket s = ss.accept()) {
                    System.out.println("Client noi tu " + s.getRemoteSocketAddress());
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    for (int i = 0; ; i++) {                    // 20 tin SIGNAL mỗi giây
                        double t = i / 20.0;
                        double amp = 20 + 0.5 * Math.sin(2 * Math.PI * 0.25 * t);
                        send(out, "{\"t\":\"SIGNAL\",\"sensor\":1,\"amp\":[" + amp + "," + amp + "]}");
                        if (i % 20 == 0) {
                            send(out, "{\"t\":\"MOTION\",\"sensor\":1,\"level\":0.73,\"moving\":true}");
                            send(out, "{\"t\":\"BREATH\",\"sensor\":1,\"bpm\":15.2,\"snr\":4.1}");
                            send(out, "{\"t\":\"STATS\",\"sensor\":1,\"pps\":98,\"loss\":0.02}");
                        }
                        if (i % 100 == 0) send(out, "{\"t\":\"PING\"}");
                        if (i % 600 == 300) {
                            send(out, "{\"t\":\"FALL\",\"sensor\":1,\"time\":"
                                    + System.currentTimeMillis() / 1000 + ",\"confidence\":0.8}");
                        }
                        Thread.sleep(50);
                    }
                } catch (IOException e) {
                    System.out.println("Client ngat: " + e.getMessage());
                }
            }
        }
    }

    static void send(DataOutputStream out, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
        out.flush();
    }
}
