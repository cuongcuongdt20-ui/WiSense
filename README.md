# WiSense

Hệ thống giám sát không tiếp xúc bằng tín hiệu Wi-Fi (CSI): phát hiện người di chuyển, đo nhịp thở và nhận diện té ngã, không cần camera hay thiết bị đeo. Bài tập lớn môn Lập trình mạng.


## Kiến trúc

Một **TCP server trung tâm** (cổng 7000) phục vụ **hai loại client** cùng lúc. Mỗi client khai báo vai trò ngay khi bắt tay:
- **client cảm biến**: ESP32, hoặc `ReplayMain --tcp` giả làm ESP32;
- **client giám sát**: ứng dụng JavaFX, hoặc `LoadTestClient`.

Server giữ trạng thái chính thức của mọi cảm biến và mọi phiên, và là nơi duy nhất quyết định kết quả phát hiện.

```text
            ping 100 lần/giây
 ESP32-S3 ◄──────────────────► Router Wi-Fi        người đi lại / thở / ngã ở khoảng giữa
 (client cảm biến)  đo CSI gói trả lời
     │ ▲
     │ │  TCP 7000 · HELLO role=sensor, rồi khung nhị phân CSI1 (~100 khung/giây)
     ▼ │  xuống: CONFIG {pingHz} → lên: CONFIG_OK         (UDP 5005 chỉ là kênh dự phòng)
┌─ SERVER (một cổng TCP, nhiều client) ─────────────────────────────
│  SensorRegistry: cảm biến online/offline, chặn trùng mã, định tuyến lệnh xuống cảm biến
│  CsiIngest ──► SensorManager ──► Motion · Breathing · Fall Detector
│  giải mã, seq    bộ đệm 60 giây,                 │ Event
│  ghi --record    STATS mỗi giây                  ▼
│                  EventLog ◄──── định tuyến sự kiện ────► FallDelivery
│                  lịch sử             │                   chờ ACK, gửi lại
│                                      ▼
│  TcpServer: mỗi kết nối một ClientSession
│      luồng đọc + luồng ghi + hàng đợi 256 tin + vai trò + trạng thái phiên
│  CommandHandler: HELLO · SUBSCRIBE · GET_HISTORY · SET_CONFIG · SET_SENSOR · ACK · BYE
└───────────────────────────────────────────────────────────────────
     ▲ │
     │ │  TCP 7000 · kết nối lâu dài, hai chiều · khung 4 byte độ dài + JSON
     │ ▼  xuống: WELCOME · OK · ERROR · HISTORY, và đẩy MOTION · BREATH · STATS · SIGNAL · FALL · SENSOR_STATUS · PING
     │    lên:   HELLO · SUBSCRIBE · GET_HISTORY · SET_CONFIG · SET_SENSOR · ACK · PONG · BYE
┌─ CLIENT GIÁM SÁT JavaFX (nhiều client cùng lúc) ──────────────────
│  ServerConnection, luồng nền: bắt tay, trả ACK, lọc FALL trùng, tự nối lại
│      ──► hàng đợi inbox ──► luồng JavaFX: biểu đồ, trạng thái, cảm biến online/offline,
│                                            lịch sử, đổi tần số cảm biến, popup té ngã
└───────────────────────────────────────────────────────────────────
```

- **Mọi thành phần nói chuyện qua TCP Client/Server.** Kết nối TCP cho biết cảm biến còn sống (rớt là biết ngay), và có sẵn kênh hai chiều để gửi lệnh xuống thiết bị. UDP (cổng 5005) chỉ giữ làm dự phòng và để so sánh TCP với UDP trong thực nghiệm.
- **Định tuyến client → server → client:** người giám sát gửi `SET_SENSOR`, server chuyển thành `CONFIG` xuống đúng cảm biến, cảm biến trả `CONFIG_OK`, server trả `OK` về đúng người đã hỏi (ghép bằng `req`). Cảm biến không trả lời trong 3 giây thì trả `TIMEOUT`.
- **Mỗi client có hàng đợi và luồng ghi riêng**, nên một client mạng chậm không làm chậm việc nhận dữ liệu cảm biến hay các client khác. Hàng đợi đầy thì bỏ SIGNAL trước, vẫn đầy thì ngắt riêng client đó.
- **FALL được giữ tới khi client ACK**, quá 3 giây thì gửi lại (tối đa 5 lần). Mục chờ lưu theo tên client, nên client rớt mạng rồi nối lại vẫn nhận được.

## Cấu trúc

```text
WiSense/
├── report/                    báo cáo, slide, kịch bản demo                    (cả nhóm)
├── data/                      dữ liệu mẫu: phòng trống, đi lại, ngồi thở, ngã  (TV1)
├── statics/results/           bảng sai số nhịp thở, ma trận nhầm lẫn té ngã    (TV3, TV4)
├── firmware/
│   ├── wisense-csi/           ESP-IDF, client cảm biến TCP (UDP dự phòng)      (TV1)
│   └── wisense-sim/           bản giả lập Wokwi, sinh CSI giả                  (TV1)
│       ├── diagram.json
│       └── wokwi.toml
└── source/                    Maven multi-module (Java 21)
    ├── common/                hợp đồng chung, server và client cùng dùng
    │   └── vn.wisense.common
    │       ├── protocol/      FrameCodec (khung 4 byte + JSON/nhị phân), Msg (loại tin, mã lỗi),  (TV1)
    │       │                  CsiCsv (gói CSI1 ↔ dòng CSV)
    │       └── model/         CsiFrame, Event, interface Detector                                (TV4)
    ├── detect/                các bộ phát hiện, cài đặt Detector
    │   └── vn.wisense.detect
    │       ├── motion/        MotionDetector                                   (TV3)
    │       ├── breathing/     BreathingEstimator (FFT, JTransforms)            (TV3)
    │       └── fall/          FallDetector                                     (TV4)
    ├── server/
    │   └── vn.wisense.server
    │       ├── udp/           CsiPacketDecoder (giải mã CSI1), PacketTracker, UdpCsiReceiver (dự phòng)  (TV2)
    │       ├── sensor/        SensorManager: bộ đệm 60 giây, gọi Detector, STATS mỗi giây        (TV2)
    │       │                  SensorRegistry: cảm biến online/offline, định tuyến lệnh           (TV1)
    │       │                  CsiIngest: điểm vào chung của gói CSI (TCP và UDP), --record        (TV1)
    │       │                  EventLog: lịch sử sự kiện cho GET_HISTORY                          (TV2)
    │       └── tcp/           TcpServer, ClientSession: một cổng, hai loại client, hàng đợi       (TV2)
    │                          CommandHandler: xử lí lệnh theo vai trò, trạng thái phiên, req     (TV2)
    │                          FallDelivery: FALL chờ ACK, gửi lại                                (TV1)
    ├── tools/                 ReplayMain: giả làm cảm biến (TCP --tcp hoặc UDP)                  (TV2)
    │                          RecordMain: ghi gói UDP từ ESP32 ra CSV                            (TV1)
    │                          EspCsiConvert: đổi CSV esp-csi (WiFall, log ESP32) sang CSV WiSense (TV1)
    │                          LoadTestClient: mở N client đo độ trễ, thông lượng, client chậm    (TV1)
    └── client/                JavaFX
        └── vn.wisense.client
            ├── net/           ServerConnection: bắt tay, gửi lệnh, ACK, tự nối lại; Message      (TV1)
            ├── ui/            biểu đồ, trạng thái, cảm biến online/offline, lịch sử, tần số      (TV1)
            └── alert/         FallAlertView: popup, âm thanh, danh sách té ngã                   (TV4)
```

## Hợp đồng chung

Mọi client nối vào **một cổng TCP 7000**. Mỗi tin là **4 byte độ dài (big-endian) rồi nội dung**, tối đa 1 MB:
- nội dung bắt đầu bằng `{` là **JSON UTF-8** (lệnh, trả lời, sự kiện), trường `t` là loại tin;
- nội dung bắt đầu bằng `CSI1` là **gói CSI nhị phân**, chỉ client cảm biến được gửi.

Đọc và ghi khung bằng `FrameCodec`; tên tin và mã lỗi lấy từ `Msg`, không gõ tay.

Gói CSI1 (big-endian; đi qua TCP thành nội dung một khung, qua UDP 5005 thì là cả datagram):

```text
"CSI1" (4) | sensorId (1) | seq (4) | timestampUs (8) | rssi (1, có dấu) | n (2) | n × [imag (1) | real (1)]
```

**Client cảm biến ↔ server:**

```json
{"t":"HELLO","ver":1,"role":"sensor","sensor":1}     ← cảm biến đăng kí
{"t":"CHALLENGE","nonce":"9f2c…","salt":"","iter":0} ← server bật xác thực (mặc định)
{"t":"AUTH","proof":"5bdc…"}                         ← HMAC-SHA256(khoá cảm biến, nonce)
                                                     ← server trả WELCOME, hoặc ERROR AUTH_FAILED / DUPLICATE_SENSOR rồi đóng
… các khung nhị phân CSI1 …
{"t":"CONFIG","req":9,"pingHz":50}                  ← server ra lệnh
{"t":"CONFIG_OK","req":9,"pingHz":50}               ← cảm biến đã áp dụng
```

**Client giám sát gửi lên** (lệnh nào cũng kèm được `"req":n`; câu trả lời, kể cả ERROR, mang lại đúng `req` đó):

```json
{"t":"HELLO","ver":1,"name":"ui-may1-1234","user":"khanh"}
{"t":"AUTH","proof":"…"}                              ← trả lời CHALLENGE {nonce, salt, iter}
{"t":"SUBSCRIBE","sensors":[1],"signal":true}
{"t":"GET_HISTORY","req":3,"sensor":1,"limit":50}
{"t":"SET_CONFIG","sensor":1,"key":"motionThr","value":0.4}
{"t":"SET_SENSOR","req":5,"sensor":1,"pingHz":50}
{"t":"ACK","id":42}
{"t":"PONG"}
{"t":"BYE"}
```

**Server trả lời lệnh:**

```json
{"t":"CHALLENGE","nonce":"…","salt":"…","iter":120000}
{"t":"WELCOME","session":7,"sensors":[1,2],"online":[1],"user":"khanh","access":"admin"}
{"t":"OK","re":"SUBSCRIBE"}
{"t":"OK","re":"SET_SENSOR","pingHz":50,"req":5}
{"t":"ERROR","code":"SENSOR_OFFLINE","msg":"...","req":5}
{"t":"HISTORY","sensor":1,"events":[{"t":"FALL",...},{"t":"BREATH",...}],"req":3}
```

**Server đẩy xuống client giám sát:** các sự kiện của cảm biến chỉ gửi cho client đã SUBSCRIBE cảm biến đó; `SENSOR_STATUS` gửi cho mọi client giám sát được xem cảm biến đó; `PING` gửi cho mọi client giám sát.

```json
{"t":"MOTION","sensor":1,"level":0.73,"moving":true,"ts":1760000000456}
{"t":"BREATH","sensor":1,"bpm":15.2,"snr":4.1,"ts":...}
{"t":"FALL","sensor":1,"time":1739000000,"confidence":0.8,"id":1760000000123,"ts":...}
{"t":"STATS","sensor":1,"pps":98,"loss":0.02,"ts":...}
{"t":"SIGNAL","sensor":1,"amp":[],"ts":...}
{"t":"SENSOR_STATUS","sensor":1,"online":false}
{"t":"PING"}
```

**Quy tắc:**

- **Xác thực (challenge–response):** HELLO được trả bằng `CHALLENGE` với `nonce` ngẫu nhiên mới mỗi lần; client gửi `AUTH {proof = HMAC-SHA256(key, nonce)}`.
  - Người dùng: `key = PBKDF2-HMAC-SHA256(mật khẩu, salt, iter)`. Server chỉ lưu `key` trong `data/users.csv`, không lưu mật khẩu, và mật khẩu không bao giờ đi trên mạng.
  - Cảm biến: `key` = khoá bí mật trong `data/sensors.csv`, nạp vào ESP32 qua `WS_SENSOR_KEY`.
  - `proof` cũ gửi lại không dùng được vì `nonce` đã khác (chống replay). Tài khoản không tồn tại vẫn nhận `CHALLENGE` (salt giả) để không dò được tên.
  - Một IP sai 5 lần trong 60 giây thì bị chặn 60 giây (`TOO_MANY_ATTEMPTS`).
  - Chạy server với `--no-auth` thì bỏ bước này: HELLO nhận WELCOME ngay, toàn quyền.
- **Phân quyền:** `admin` được xem và điều khiển (`SET_CONFIG`, `SET_SENSOR`); `viewer` chỉ được xem. Mỗi tài khoản chỉ thấy các cảm biến được cấp (`WELCOME.sensors`, `SENSOR_STATUS`); `SUBSCRIBE`, `GET_HISTORY` hay lệnh điều khiển trên cảm biến khác nhận `FORBIDDEN` (giữ kết nối).
- **Lệnh quản trị** (chỉ `admin`, client có cửa sổ "Quản trị"; viewer nhận `FORBIDDEN`):
  - `LIST_SENSORS` → `SENSORS {sensors:[{sensor, online, tcp, key}]}`; `NEW_SENSOR_KEY {sensor}` → `SENSOR_KEY {sensor, secret}` (cảm biến đang nối bằng khoá cũ bị ngắt); `DELETE_SENSOR_KEY {sensor}`.
  - `LIST_USERS` → `USERS`; `SAVE_USER {user, access, sensors, salt, iter, key}` (client băm mật khẩu, server không nhận mật khẩu); `DELETE_USER {user}`. Phiên đang mở của tài khoản bị sửa hoặc xoá nhận `ERROR KICKED` rồi bị đóng. Không tự xoá mình, không hạ quyền admin cuối cùng.
  - `LIST_SESSIONS` → `SESSIONS {sessions:[{session, role, name, user, address, state}]}`; `KICK {session}` ngắt một kết nối.
  - Thay đổi được ghi ngay vào `data/users.csv`, `data/sensors.csv`, không cần khởi động lại server.
- **Trạng thái phiên:** `CONNECTED → CHALLENGED` (sau HELLO) `→ AUTHED` (sau AUTH đúng) `→ SUBSCRIBED → CLOSED`. Lệnh gửi trước khi đăng nhập xong thì nhận `INVALID_STATE`. Client cảm biến chỉ được gửi `CONFIG_OK`, `PONG`, `BYE` (ngoài các khung CSI); client giám sát không được gửi khung nhị phân.
- **Mã lỗi:**
  - `BAD_JSON`: không parse được hoặc thiếu `t`;
  - `UNKNOWN_TYPE`, `INVALID_STATE`, `BAD_ARG`;
  - `SENSOR_OFFLINE`: cảm biến không nối TCP;
  - `DUPLICATE_SENSOR`: mã cảm biến đang có người dùng; server gửi lỗi rồi đóng kết nối;
  - `TIMEOUT`: cảm biến không trả `CONFIG_OK` trong 3 giây;
  - `AUTH_FAILED`: sai tài khoản, mật khẩu hoặc khoá cảm biến; server gửi lỗi rồi đóng kết nối;
  - `TOO_MANY_ATTEMPTS`: IP đang bị chặn vì sai nhiều lần; server gửi lỗi rồi đóng kết nối;
  - `FORBIDDEN`: tài khoản không có quyền với lệnh hoặc cảm biến đó;
  - `KICKED`: admin ngắt phiên này, hoặc tài khoản/khoá vừa bị đổi; server gửi lỗi rồi đóng, client không tự nối lại.
  
  Lỗi lệnh thì trả ERROR và **giữ kết nối**. Chỉ đóng khi độ dài khung sai (luồng byte đã lệch), khi đăng nhập sai và khi trùng mã cảm biến.
- **Cảm biến online/offline:** online khi có khung CSI trong 5 giây gần nhất; cảm biến TCP đóng kết nối thì offline ngay. Mỗi lần đổi trạng thái, server đẩy `SENSOR_STATUS`.
- **FALL** có `id` (bắt đầu từ giờ hệ thống, nên không lặp lại khi server khởi động lại) và `ts`. Client trả ACK cho mọi bản nhận được, kể cả bản gửi lại, và chỉ báo một lần cho mỗi `id`. Server gửi lại sau 3 giây, tối đa 5 lần; client HELLO lại cùng `name` thì nhận ngay các FALL còn chờ.
- **Giữ kết nối:** server gửi PING cho client giám sát mỗi 5 giây; hai phía coi là rớt nếu 15 giây không có tin. Client chờ 2 giây rồi nối lại, gửi lại HELLO và SUBSCRIBE. Cảm biến không nhận PING: dòng CSI liên tục đã cho biết nó còn sống.

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

Tạo tài khoản và khoá cảm biến **một lần** trên máy server (chạy trong `source/`, ghi vào `data/users.csv` và `data/sensors.csv`; hai file này chứa bí mật, đã có trong `.gitignore`):

```bash
java -cp tools/target/wisense-tools.jar vn.wisense.tools.UserAdmin --add=khanh --access=admin         # hỏi mật khẩu
java -cp tools/target/wisense-tools.jar vn.wisense.tools.UserAdmin --add=khach --access=viewer --see=1
java -cp tools/target/wisense-tools.jar vn.wisense.tools.UserAdmin --add-sensor=1    # in khoá của cảm biến 1
java -cp tools/target/wisense-tools.jar vn.wisense.tools.UserAdmin --list
```

Chạy hệ thống **không cần ESP32** (chạy trong `source/`):

```bash
java -jar server/target/wisense-server.jar --udp-port=0          # TCP 7000, bắt buộc đăng nhập (UDP không xác thực nên tắt)
java -jar tools/target/wisense-tools.jar --tcp --key=<khoá cảm biến 1>          # giả làm cảm biến 1 qua TCP
java -jar tools/target/wisense-tools.jar --tcp --sensor=2 --key=<khoá cảm biến 2>
java -jar client/target/wisense-client.jar                       # màn đăng nhập: server, cổng, tài khoản, mật khẩu
```

Chạy thử nhanh không cần tài khoản: thêm `--no-auth` cho server, bỏ `--key` ở cảm biến; màn đăng nhập nhận mật khẩu bất kỳ. Đo tải có đăng nhập: `LoadTestClient ... --user=khanh --password=...`.

Ghi dữ liệu thật từ ESP32 rồi phát lại:

```bash
# ESP32 nối TCP: server tự ghi mọi gói nhận được ra CSV
java -jar server/target/wisense-server.jar --record=../data/fall_01.csv
# phát lại như một cảm biến TCP
java -jar tools/target/wisense-tools.jar --tcp --file=../data/fall_01.csv

# ESP32 chọn UDP (dự phòng): RecordMain nghe 5005, ghi file và chuyển tiếp cho server ở 5006
java -jar server/target/wisense-server.jar --udp-port=5006
java -cp tools/target/wisense-tools.jar vn.wisense.tools.RecordMain --out=../data/walk_01.csv --seconds=60 --forward=127.0.0.1:5006
```

File CSV có dạng `sensorId,seq,timestampUs,rssi,imag0,real0,...`, dòng bắt đầu bằng `#` là ghi chú. Bỏ `--seconds` thì ghi tới khi bấm Ctrl+C.

**Dữ liệu thật công khai (WiFall, thu bằng ESP32-S3):**
- Bộ [WiFall](https://huggingface.co/datasets/RS2002/WiFall), Zhao và cộng sự, 2025, [arXiv:2412.04783](https://arxiv.org/abs/2412.04783): 10 người, mỗi file khoảng 3 giây quanh một hành động (fall, jump, sit, stand, walk), khoảng 83 gói/giây, 52 sóng mang con.
- Dữ liệu không nằm trong repo; tải về rồi chuyển đổi bằng `EspCsiConvert`. Công cụ này ghép các đoạn thành một luồng liên tục và ghi `# MARK n nhãn` ở đầu mỗi đoạn, để biết cú ngã thật nằm ở đâu.

```bash
curl -L -o WiFall.zip https://huggingface.co/datasets/RS2002/WiFall/resolve/main/WiFall.zip && unzip WiFall.zip
java -cp tools/target/wisense-tools.jar vn.wisense.tools.EspCsiConvert --in=WiFall/ID0 --out=../data/wifall_id0.csv
java -jar tools/target/wisense-tools.jar --tcp --file=../data/wifall_id0.csv --interval-ms=12
```

Đo hiệu năng phần TCP (chạy cùng máy với server để dùng chung đồng hồ):

```bash
java -cp tools/target/wisense-tools.jar vn.wisense.tools.LoadTestClient --clients=50 --seconds=30 --label=c50
java -cp tools/target/wisense-tools.jar vn.wisense.tools.LoadTestClient --clients=10 --slow-clients=1 --slow-ms=500 --seconds=90 --label=slow
```

Mỗi client ghi một dòng vào `statics/results/loadtest.csv`: tin/giây, byte/giây, độ trễ p50/p95/max (giờ nhận − `ts`), số FALL và bản trùng, thời gian bắt tay, có bị ngắt không. Tuỳ chọn khác: `--no-ack`, `--no-signal`, `--sensors=1,2`, `--host`, `--port`.

## Thư viện ngoài

- Gson 2.11.0: đọc ghi JSON
- JTransforms 3.1: FFT cho nhịp thở
- JavaFX 21.0.5: giao diện
- JUnit 5.11.0: kiểm thử

## Lớp chính cần đặt đúng tên

- `vn.wisense.server.ServerMain`: lớp `main` của `server/target/wisense-server.jar`
- `vn.wisense.tools.ReplayMain`: lớp `main` của `tools/target/wisense-tools.jar`
- `vn.wisense.tools.RecordMain`, `vn.wisense.tools.LoadTestClient`, `vn.wisense.tools.EspCsiConvert`: nằm trong cùng jar, chạy bằng `java -cp tools/target/wisense-tools.jar <tên lớp>`
- `vn.wisense.client.Launcher`: lớp `main` của `client/target/wisense-client.jar` (không kế thừa `Application`, chỉ gọi lớp JavaFX chính, để jar gộp chạy được)

### Motion Detection Threshold (`motionThr`)

The `motionThr` configuration key controls the absolute threshold for motion detection. The detector compares the normalized amplitude variance (CV²) of the current window against this threshold.

- **Automatic mode:** Set `motionThr = 0` to return to automatic thresholding. The detector uses `max(minThreshold, k × baseline)`.
- **Manual mode:** Set `motionThr` to a finite positive number to use that absolute CV² threshold.
- **Units:** Dimensionless normalized variance (CV²), not a percentage or a multiplier.
- **Choosing a value:** The appropriate threshold depends on the sensor and its environment. Check the logged `baselineCv2` value and validate candidate thresholds against both still-room and movement data before deploying them. Do not assume one fixed value is suitable for every sensor.
