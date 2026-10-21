#pragma once
#include <stddef.h>
#include <stdint.h>
#include "lwip/sockets.h"

#define WS_HEADER_LEN 20               // 4 + 1 + 4 + 8 + 1 + 2
#define WS_MAX_SUBCARRIERS 192
#define WS_MAX_PACKET (WS_HEADER_LEN + 2 * WS_MAX_SUBCARRIERS)

// Kết nối Wi-Fi chế độ STA, chờ đến khi có IP. Rớt mạng thì tự nối lại.
void ws_wifi_connect(const char *ssid, const char *pass);

// BSSID (MAC) của router đang nối; cập nhật lại mỗi lần nối lại. Dùng để lọc CSI theo router.
const uint8_t *ws_wifi_bssid(void);

// Kênh Wi-Fi của router đang nối (0 nếu chưa nối).
uint8_t ws_wifi_channel(void);

// Mở socket UDP và phân giải địa chỉ server; trả về -1 nếu lỗi
int ws_udp_open(const char *host, uint16_t port, struct sockaddr_in *dest);

// Như ws_udp_open nhưng thử lại mỗi giây cho tới khi được (server/DNS chưa sẵn sàng lúc khởi động)
int ws_udp_open_retry(const char *host, uint16_t port, struct sockaddr_in *dest);

// Kết nối TCP tới server (tắt Nagle để gói nhỏ đi ngay); trả về socket hoặc -1 nếu lỗi
int ws_tcp_connect(const char *host, uint16_t port);

// Gửi một khung: 4 byte độ dài (big-endian) rồi nội dung, gửi lặp tới khi đủ. 0 = thành công, -1 = lỗi.
// Nhiều task cùng gửi trên một socket thì bên gọi phải khoá.
int ws_send_frame(int sock, const void *data, size_t len);

// Nhận một khung vào buf (tối đa cap byte). Trả về độ dài, hoặc -1 nếu lỗi/khung quá dài/server đóng.
int ws_recv_frame(int sock, uint8_t *buf, size_t cap);

// Đóng gói theo hợp đồng chung:
// "CSI1" (4) | sensorId (1) | seq (4) | timestampUs (8) | rssi (1) | n (2) | n × [imag (1) | real (1)]
// csi: n cặp [imag, real] liên tiếp, đúng thứ tự buf của esp_wifi
size_t ws_build_packet(uint8_t *out, uint8_t sensor_id, uint32_t seq,
                       uint64_t timestamp_us, int8_t rssi,
                       const int8_t *csi, uint16_t n);

// Kẹp về khoảng int8 của hợp đồng (dùng sau khi bù độ lợi hoặc khi sinh CSI giả)
static inline int8_t ws_clamp_i8(int v) {
    return (int8_t) (v > 127 ? 127 : (v < -128 ? -128 : v));
}
