# WiSense

Hệ thống giám sát không tiếp xúc bằng tín hiệu Wi-Fi (CSI): phát hiện người di chuyển, đo nhịp thở và nhận diện té ngã, không cần camera hay thiết bị đeo. Bài tập lớn môn Lập trình mạng.

> Đây là khung dự án, chưa có mã nguồn. Kế hoạch đầy đủ nằm ở tài liệu "WiSense — Kế hoạch dự án".

## Kiến trúc

```text
ESP32-S3 ──UDP 5005──▶ Server ──TCP 7000──▶ Client JavaFX
 (cảm biến)            │  ▲
                       ▼  │ Detector
                 MotionDetector · BreathingEstimator · FallDetector
```

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
    ├── common/                hợp đồng chung, cả nhóm chốt ngày 1
    │   └── vn.wisense.common
    │       ├── protocol/      giải mã gói UDP CSI, đóng khung tin TCP JSON
    │       └── model/         CsiFrame, Event, interface Detector
    ├── detect/                các bộ phát hiện, cài đặt Detector
    │   └── vn.wisense.detect
    │       ├── motion/        MotionDetector                                   (TV3)
    │       ├── breathing/     BreathingEstimator (FFT, JTransforms)            (TV3)
    │       └── fall/          FallDetector                                     (TV4)
    ├── server/                                                                 (TV2)
    │   └── vn.wisense.server
    │       ├── udp/           nhận UDP, theo dõi seq, mất gói, gói mỗi giây
    │       ├── sensor/        quản lý cảm biến, bộ đệm vòng 60 giây, gọi Detector
    │       └── tcp/           TCP server nhiều client, PING/PONG
    ├── tools/                 phát lại file dữ liệu thành UDP giả, ghi mẫu     (TV2)
    └── client/                JavaFX
        └── vn.wisense.client
            ├── net/           kết nối server, đọc tin                          (TV1)
            ├── ui/            biểu đồ tín hiệu, trạng thái, nhịp thở           (TV1)
            └── alert/         popup, âm thanh, lịch sử té ngã                  (TV4)
```

## Hợp đồng chung

Gói UDP từ ESP32 về server (cổng 5005, big-endian):

```text
"CSI1" (4) | sensorId (1) | seq (4) | timestampUs (8) | rssi (1, có dấu) | n (2) | n × [imag (1) | real (1)]
```

Tin TCP từ server tới client (cổng 7000): 4 byte độ dài, sau đó là JSON.

```json
{"t":"MOTION","sensor":1,"level":0.73,"moving":true}
{"t":"BREATH","sensor":1,"bpm":15.2,"snr":4.1}
{"t":"FALL","sensor":1,"time":1739000000,"confidence":0.8}
{"t":"STATS","sensor":1,"pps":98,"loss":0.02}
{"t":"SIGNAL","sensor":1,"amp":[]}
```

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
