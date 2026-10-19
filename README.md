# WiSense

Hệ thống giám sát không tiếp xúc bằng tín hiệu Wi-Fi (CSI): phát hiện người di chuyển, đo nhịp thở và nhận diện té ngã, không cần camera hay thiết bị đeo. Bài tập lớn môn Lập trình mạng.


## Kiến trúc

Mô hình Client/Server ba tầng: cảm biến → server → các client giám sát. Server giữ trạng thái chính thức của từng cảm biến và từng phiên kết nối.

```text
            ping 100 lần/giây
 ESP32-S3 ◄──────────────────► Router Wi-Fi        người đi lại / thở / ngã ở khoảng giữa
 (cảm biến)  đo CSI gói trả lời
     │
     │ UDP 5005 · gói nhị phân CSI1 · khoảng 100 gói/giây · chấp nhận mất gói
     ▼
┌─ SERVER ──────────────────────────────────────────────────────────
│  UdpCsiReceiver ──► SensorManager ──► Motion · Breathing · Fall Detector
│  seq, mất gói,      bộ đệm 60 giây,                │ Event
│  gói trùng          STATS mỗi giây                 ▼
│                     EventLog ◄──── định tuyến sự kiện ────► FallDelivery
│                     lịch sử             │                   chờ ACK, gửi lại
│                                         ▼
│  TcpServer: mỗi client một ClientSession
│      luồng đọc + luồng ghi + hàng đợi 256 tin + trạng thái phiên + sensor đã SUBSCRIBE
│  CommandHandler: HELLO · SUBSCRIBE · GET_HISTORY · SET_CONFIG · ACK · BYE
└───────────────────────────────────────────────────────────────────
     ▲ │
     │ │  TCP 7000 · kết nối lâu dài, hai chiều · khung 4 byte độ dài + JSON
     │ ▼  xuống: WELCOME · OK · ERROR · HISTORY, và đẩy MOTION · BREATH · STATS · SIGNAL · FALL · PING
     │    lên:   HELLO · SUBSCRIBE · GET_HISTORY · SET_CONFIG · ACK · PONG · BYE
┌─ CLIENT JavaFX (nhiều client cùng lúc) ───────────────────────────
│  ServerConnection, luồng nền: bắt tay, trả ACK, lọc FALL trùng, tự nối lại
│      ──► hàng đợi inbox ──► luồng JavaFX: biểu đồ, trạng thái, lịch sử, popup té ngã
└───────────────────────────────────────────────────────────────────
```

- **UDP** cho dữ liệu cảm biến: nhiều, liên tục, mất vài gói không sao, cần độ trễ thấp.
- **TCP** cho server ↔ client: lệnh, trả lời và cảnh báo phải đến đủ và đúng thứ tự.
- Luồng nhận UDP chỉ xếp tin vào hàng đợi của từng client. Mỗi client có luồng ghi riêng, nên một client mạng chậm không làm chậm việc nhận UDP hay các client khác. Hàng đợi đầy thì bỏ SIGNAL trước, vẫn đầy thì ngắt riêng client đó.
- FALL được giữ tới khi client ACK, quá 3 giây thì gửi lại (tối đa 5 lần). Mục chờ lưu theo tên client, nên client rớt mạng rồi nối lại vẫn nhận được.

## Cấu trúc

```text
WiSense/
├── report/                    báo cáo, slide, kịch bản demo                    (TV4)
├── data/                      dữ liệu mẫu: phòng trống, đi lại, ngồi thở, ngã  (TV1)
├── statics/results/           bảng sai số nhịp thở, ma trận nhầm lẫn té ngã    (TV3, TV4)
├── firmware/
│   ├── wisense-csi/           ESP-IDF, sửa từ csi_recv của esp-csi, gửi UDP    (TV1)
│   └── wisense-sim/           bản giả lập Wokwi, sinh CSI giả                  (TV1)
│       ├── diagram.json
│       └── wokwi.toml
└── source/                    Maven multi-module (Java 21)
    ├── common/                hợp đồng chung, server và client cùng dùng
    │   └── vn.wisense.common
    │       ├── protocol/      FrameCodec (khung 4 byte + JSON), Msg (loại tin, mã lỗi, tạo lệnh)  (TV1)
    │       └── model/         CsiFrame, Event, interface Detector                                (TV4)
    ├── detect/                các bộ phát hiện, cài đặt Detector
    │   └── vn.wisense.detect
    │       ├── motion/        MotionDetector                                   (TV3)
    │       ├── breathing/     BreathingEstimator (FFT, JTransforms)            (TV3)
    │       └── fall/          FallDetector                                     (TV4)
    ├── server/
    │   └── vn.wisense.server
    │       ├── udp/           UdpCsiReceiver, CsiPacketDecoder (giải mã CSI1), PacketTracker      (TV2)
    │       ├── sensor/        SensorManager: bộ đệm 60 giây, gọi Detector, STATS mỗi giây        (TV2)
    │       │                  EventLog: lịch sử sự kiện cho GET_HISTORY (đang làm)               (TV3)
    │       └── tcp/           TcpServer, ClientSession: nhiều client, hàng đợi, PING/PONG         (TV2)
    │                          CommandHandler: xử lí lệnh, trạng thái phiên (đang làm)            (TV2)
    │                          FallDelivery: FALL chờ ACK, gửi lại                                (TV1)
    ├── tools/                 ReplayMain: phát lại file thành UDP giả                             (TV2)
    │                          LoadTestClient: mở N client đo độ trễ, thông lượng (đang làm)      (TV3)
    └── client/                JavaFX
        └── vn.wisense.client
            ├── net/           ServerConnection: bắt tay, gửi lệnh, ACK, tự nối lại; Message      (TV1)
            ├── ui/            biểu đồ tín hiệu, trạng thái, chọn cảm biến, xem lịch sử           (TV1)
            └── alert/         popup, âm thanh, danh sách té ngã (đang làm)                       (TV4)
```

## Hợp đồng chung

Gói UDP từ ESP32 về server (cổng 5005, big-endian):

```text
"CSI1" (4) | sensorId (1) | seq (4) | timestampUs (8) | rssi (1, có dấu) | n (2) | n × [imag (1) | real (1)]
```

Tin TCP giữa server và client (cổng 7000), hai chiều: mỗi tin là 4 byte độ dài (big-endian) rồi JSON UTF-8, tối đa 1 MB. Trường `t` là loại tin. Đọc và ghi khung bằng `FrameCodec`; tên tin và mã lỗi lấy từ `Msg`, không gõ tay.

Client gửi lên:

```json
{"t":"HELLO","ver":1,"name":"ui-may1-1234"}
{"t":"SUBSCRIBE","sensors":[1],"signal":true}
{"t":"GET_HISTORY","sensor":1,"limit":50}
{"t":"SET_CONFIG","sensor":1,"key":"motionThr","value":0.4}
{"t":"ACK","id":42}
{"t":"PONG"}
{"t":"BYE"}
```

Server trả lời lệnh:

```json
{"t":"WELCOME","session":7,"sensors":[1,2]}
{"t":"OK","re":"SUBSCRIBE"}
{"t":"ERROR","code":"INVALID_STATE","msg":"..."}
{"t":"HISTORY","sensor":1,"events":[{"t":"FALL",...},{"t":"BREATH",...}]}
```

Server đẩy xuống, chỉ cho client đã SUBSCRIBE cảm biến đó (PING thì gửi mọi client):

```json
{"t":"MOTION","sensor":1,"level":0.73,"moving":true}
{"t":"BREATH","sensor":1,"bpm":15.2,"snr":4.1}
{"t":"FALL","sensor":1,"time":1739000000,"confidence":0.8,"id":42}
{"t":"STATS","sensor":1,"pps":98,"loss":0.02}
{"t":"SIGNAL","sensor":1,"amp":[]}
{"t":"PING"}
```

Quy tắc:

- Trạng thái phiên: `CONNECTED → AUTHED` (sau HELLO) `→ SUBSCRIBED → CLOSED`. Gửi SUBSCRIBE, GET_HISTORY, SET_CONFIG trước HELLO thì nhận `INVALID_STATE`.
- Mã lỗi: `BAD_JSON` (không parse được, thiếu `t`), `UNKNOWN_TYPE`, `INVALID_STATE`, `BAD_ARG`. Lỗi lệnh thì trả ERROR và **giữ kết nối**; chỉ khi độ dài khung sai mới đóng, vì luồng byte đã lệch.
- FALL có `id`. Client trả ACK cho mọi bản nhận được, kể cả bản gửi lại, và chỉ báo một lần cho mỗi `id`. Server gửi lại sau 3 giây, tối đa 5 lần; client HELLO lại cùng `name` thì nhận ngay các FALL còn chờ.
- Server gửi PING mỗi 5 giây; hai phía coi là rớt nếu 15 giây không có tin. Client chờ 2 giây rồi nối lại, gửi lại HELLO và SUBSCRIBE.

Giao diện giữa server và các bộ phát hiện:

```java
record CsiFrame(int sensorId, long seq, long timestampUs, int rssi, float[] amplitude, float[] phase) {}

interface Detector {          // TV3 và TV4 cài đặt, TV2 gọi
    void onFrame(CsiFrame f);
    List<Event> poll();       // sự kiện mới để gửi tới client
}
```

## Môi trường

- Java 21 trở lên, Maven 3.9 trở lên
- ESP-IDF (cho firmware), VS Code + extension Wokwi Simulator (cho bản giả lập)

```bash
cd source
mvn clean verify
```

Lệnh trên chạy được ngay trên khung rỗng và tải sẵn các thư viện về máy.

## Thư viện ngoài

- Gson 2.11.0: đọc ghi JSON
- JTransforms 3.1: FFT cho nhịp thở
- JavaFX 21.0.5: giao diện
- JUnit 5.11.0: kiểm thử

## Lớp chính cần đặt đúng tên

- `vn.wisense.server.ServerMain`: lớp `main` của `server/target/wisense-server.jar`
- `vn.wisense.tools.ReplayMain`: lớp `main` của `tools/target/wisense-tools.jar`
- `vn.wisense.client.Launcher`: lớp `main` của `client/target/wisense-client.jar` (không kế thừa `Application`, chỉ gọi lớp JavaFX chính, để jar gộp chạy được)
