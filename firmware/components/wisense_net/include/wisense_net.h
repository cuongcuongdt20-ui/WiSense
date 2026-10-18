#pragma once
#include <stddef.h>
#include <stdint.h>
#include "lwip/sockets.h"

#define WS_HEADER_LEN 20               // 4 + 1 + 4 + 8 + 1 + 2
#define WS_MAX_SUBCARRIERS 192
#define WS_MAX_PACKET (WS_HEADER_LEN + 2 * WS_MAX_SUBCARRIERS)

// Kết nối Wi-Fi chế độ STA, chờ đến khi có IP
void ws_wifi_connect(const char *ssid, const char *pass);

// Mở socket UDP và phân giải địa chỉ server; trả về -1 nếu lỗi
int ws_udp_open(const char *host, uint16_t port, struct sockaddr_in *dest);

// Đóng gói theo hợp đồng chung:
// "CSI1" (4) | sensorId (1) | seq (4) | timestampUs (8) | rssi (1) | n (2) | n × [imag (1) | real (1)]
// csi: n cặp [imag, real] liên tiếp, đúng thứ tự buf của esp_wifi
size_t ws_build_packet(uint8_t *out, uint8_t sensor_id, uint32_t seq,
                       uint64_t timestamp_us, int8_t rssi,
                       const int8_t *csi, uint16_t n);
