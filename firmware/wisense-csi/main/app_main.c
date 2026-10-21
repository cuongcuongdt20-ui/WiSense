// Firmware thật cho ESP32-S3: lấy CSI rồi gửi về server theo hợp đồng "CSI1".
// Cấu hình bằng idf.py menuconfig → WiSense. Hai cách gửi:
//  - TCP (mặc định): ESP32 là client cảm biến của server. Kết nối, gửi HELLO role=sensor, chờ WELCOME, rồi gửi
//    mỗi gói CSI1 thành một khung (4 byte độ dài + gói). Một task riêng nhận lệnh CONFIG từ server (đổi tần số
//    ping) và trả CONFIG_OK. Mất kết nối thì tự nối lại.
//  - UDP (dự phòng): gửi một chiều tới cổng UDP của server như bản cũ.
// Có hai nguồn CSI:
//  - ROUTER (mặc định, chỉ cần 1 board): theo ví dụ csi_recv_router của esp-csi. Board nối router,
//    ping gateway WS_PING_HZ lần/giây; CSI lấy từ gói trả lời của router (lọc theo BSSID).
//  - ESPNOW (2 board): theo ví dụ csi_recv của esp-csi. Nhận gói ESP-NOW của con phát chạy csi_send
//    (lọc theo MAC). Con phát phải phát đúng kênh của router: xem dòng log "kenh router".
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "mbedtls/md.h"
#include "ping/ping_sock.h"
#include "wisense_net.h"

// Bù AGC bằng esp_csi_gain_ctrl như esp-csi; thư viện này chỉ hỗ trợ một số chip
#if CONFIG_IDF_TARGET_ESP32S3 || CONFIG_IDF_TARGET_ESP32C3
#define WS_GAIN_CONTROL 1
#include "esp_csi_gain_ctrl.h"
#endif

#define GAIN_BASELINE_FRAMES 100            // số khung đầu để học độ lợi nền
#define STATUS_PERIOD_MS 5000

typedef struct {
    uint32_t seq;
    int64_t ts_us;
    int8_t rssi;
    uint16_t len;                                   // số byte trong buf = 2 × số sóng mang con
    int8_t buf[2 * WS_MAX_SUBCARRIERS];
} csi_item_t;

static const char *TAG = "wisense-csi";
static QueueHandle_t s_queue;
static uint32_t s_seq;                              // tăng trong callback để server thấy cả gói bị bỏ
static volatile uint32_t s_dropped;                 // khung bỏ vì hàng đợi đầy
#if CONFIG_WS_SOURCE_ESPNOW
static uint8_t s_sender_mac[6];
#endif

#if WS_GAIN_CONTROL
// AGC đổi độ lợi làm cả khung CSI to/nhỏ theo, dễ bị hiểu nhầm là có người chuyển động.
// Học độ lợi nền trong 100 khung đầu rồi bù (hoặc khóa cứng độ lợi nếu bật WS_FORCE_GAIN).
static float gain_compensation(const wifi_pkt_rx_ctrl_t *rx_ctrl) {
    static uint32_t count;
    uint8_t agc_gain = 0;
    int8_t fft_gain = 0;
    float compensate = 1.0f;
    esp_csi_gain_ctrl_get_rx_gain(rx_ctrl, &agc_gain, &fft_gain);
    if (count < GAIN_BASELINE_FRAMES) {
        esp_csi_gain_ctrl_record_rx_gain(agc_gain, fft_gain);
    } else if (count == GAIN_BASELINE_FRAMES) {
        uint8_t agc_base = 0;
        int8_t fft_base = 0;
        esp_csi_gain_ctrl_get_rx_gain_baseline(&agc_base, &fft_base);
#if CONFIG_WS_FORCE_GAIN
        esp_csi_gain_ctrl_set_rx_force_gain(agc_base, fft_base);
#endif
    }
    count++;
    esp_csi_gain_ctrl_get_gain_compensation(&compensate, agc_gain, fft_gain);
    return compensate;
}
#else
static float gain_compensation(const wifi_pkt_rx_ctrl_t *rx_ctrl) {
    return 1.0f;
}
#endif

static bool from_source(const uint8_t *mac) {
#if CONFIG_WS_SOURCE_ESPNOW
    return memcmp(mac, s_sender_mac, 6) == 0;
#else
    return memcmp(mac, ws_wifi_bssid(), 6) == 0;
#endif
}

// Chạy trong task Wi-Fi: chỉ chép dữ liệu vào hàng đợi, không log, không chặn
static void csi_cb(void *ctx, wifi_csi_info_t *info) {
    if (!info || !info->buf || info->len < 2 || !from_source(info->mac)) return;
    csi_item_t item;
    item.seq = s_seq++;
    item.ts_us = esp_timer_get_time();
    item.rssi = info->rx_ctrl.rssi;
    item.len = (info->len > sizeof(item.buf) ? sizeof(item.buf) : info->len) & ~1u;
    float g = gain_compensation(&info->rx_ctrl);
    for (int i = 0; i < item.len; i++) item.buf[i] = ws_clamp_i8((int) lroundf(g * info->buf[i]));
    // Một số chip báo 4 byte đầu không hợp lệ: đặt về 0 như sóng mang con rỗng
    if (info->first_word_invalid) memset(item.buf, 0, item.len < 4 ? item.len : 4);
    if (xQueueSend(s_queue, &item, 0) != pdTRUE) s_dropped++;   // đầy thì bỏ, không chặn task Wi-Fi
}

#if CONFIG_WS_SOURCE_ROUTER
static esp_ping_handle_t s_ping;                    // phiên ping hiện tại, đổi được khi server gửi CONFIG

// Tạo lưu lượng đều để router trả lời liên tục, mỗi gói trả lời cho một khung CSI (như csi_recv_router).
// Gọi lại với hz khác thì dừng phiên cũ và mở phiên mới.
static void ping_gateway_set_hz(int hz) {
    if (s_ping) {
        esp_ping_stop(s_ping);
        esp_ping_delete_session(s_ping);
        s_ping = NULL;
    }
    esp_netif_ip_info_t ip;
    esp_netif_get_ip_info(esp_netif_get_handle_from_ifkey("WIFI_STA_DEF"), &ip);
    esp_ping_config_t pc = ESP_PING_DEFAULT_CONFIG();
    pc.count = ESP_PING_COUNT_INFINITE;
    pc.interval_ms = 1000 / hz;
    pc.task_stack_size = 3072;
    pc.data_size = 1;
    pc.target_addr.u_addr.ip4.addr = ip4_addr_get_u32(&ip.gw);
    pc.target_addr.type = ESP_IPADDR_TYPE_V4;
    esp_ping_callbacks_t cbs = {0};
    ESP_ERROR_CHECK(esp_ping_new_session(&pc, &cbs, &s_ping));
    ESP_ERROR_CHECK(esp_ping_start(s_ping));
    ESP_LOGI(TAG, "ping gateway " IPSTR " %d lan/giay", IP2STR(&ip.gw), hz);
}
#endif

#if CONFIG_WS_TRANSPORT_TCP
static SemaphoreHandle_t s_tx_lock;                 // send_task gửi CSI, recv_task gửi CONFIG_OK: khoá để khung không xen nhau

static int send_frame_locked(int sock, const void *data, size_t len) {
    xSemaphoreTake(s_tx_lock, portMAX_DELAY);
    int r = ws_send_frame(sock, data, len);
    xSemaphoreGive(s_tx_lock);
    return r;
}

// Lấy số nguyên sau "key": trong một tin JSON ngắn; không có thì trả -1. Tin từ server do server tự tạo nên đủ dùng.
static long json_long(const char *json, const char *key) {
    const char *p = strstr(json, key);
    if (!p) return -1;
    return strtol(p + strlen(key), NULL, 10);
}

// Nhận lệnh từ server trên một kết nối; kết nối đóng (send_task đóng hoặc server ngắt) thì task tự kết thúc.
static void recv_task(void *arg) {
    int sock = (int) (intptr_t) arg;
    uint8_t buf[256];
    for (;;) {
        int len = ws_recv_frame(sock, buf, sizeof(buf) - 1);
        if (len < 0) break;
        buf[len] = 0;
        const char *json = (const char *) buf;
        if (!strstr(json, "\"t\":\"CONFIG\"")) continue;
        long req = json_long(json, "\"req\":");
        long hz = json_long(json, "\"pingHz\":");
        if (hz < 1 || hz > 200) continue;
#if CONFIG_WS_SOURCE_ROUTER
        ping_gateway_set_hz((int) hz);
#endif
        char ok[96];
        int n = snprintf(ok, sizeof(ok), "{\"t\":\"CONFIG_OK\",\"req\":%ld,\"pingHz\":%ld}", req, hz);
        send_frame_locked(sock, ok, (size_t) n);
        ESP_LOGI(TAG, "server yeu cau %ld lan ping/giay: da ap dung", hz);
    }
    vTaskDelete(NULL);
}

// Trả lời CHALLENGE: proof = hex(HMAC-SHA256(khoá cảm biến, chuỗi nonce)), cùng phép tính với Auth.proof bên Java.
// Ghi tin AUTH vào out; trả về độ dài, hoặc -1 nếu tin không có nonce hợp lệ.
static int build_auth(const char *challenge, char *out, size_t cap) {
    const char *p = strstr(challenge, "\"nonce\":\"");
    if (!p) return -1;
    p += strlen("\"nonce\":\"");
    const char *end = strchr(p, '"');
    if (!end || end - p > 64) return -1;
    uint8_t mac[32];
    const char *key = CONFIG_WS_SENSOR_KEY;
    if (mbedtls_md_hmac(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), (const unsigned char *) key, strlen(key),
                        (const unsigned char *) p, (size_t) (end - p), mac) != 0) return -1;
    char hex[65];
    for (int i = 0; i < 32; i++) snprintf(hex + 2 * i, 3, "%02x", mac[i]);
    return snprintf(out, cap, "{\"t\":\"AUTH\",\"proof\":\"%s\"}", hex);
}

// Kết nối và đăng kí với server; thử lại mỗi 2 giây tới khi được WELCOME. Trả về socket đã đăng kí.
// Server bật xác thực thì HELLO được trả CHALLENGE: gửi AUTH rồi mới nhận WELCOME.
static int tcp_register(void) {
    char hello[96];
    int n = snprintf(hello, sizeof(hello), "{\"t\":\"HELLO\",\"ver\":1,\"role\":\"sensor\",\"sensor\":%d}",
                     CONFIG_WS_SENSOR_ID);
    for (;;) {
        int wait_ms = 2000;
        int sock = ws_tcp_connect(CONFIG_WS_SERVER_HOST, CONFIG_WS_SERVER_TCP_PORT);
        if (sock >= 0 && ws_send_frame(sock, hello, (size_t) n) == 0) {
            uint8_t buf[256];
            int len = ws_recv_frame(sock, buf, sizeof(buf) - 1);
            if (len > 0) {
                buf[len] = 0;
                if (strstr((const char *) buf, "\"CHALLENGE\"")) {
                    char auth[128];
                    int an = build_auth((const char *) buf, auth, sizeof(auth));
                    if (an > 0 && ws_send_frame(sock, auth, (size_t) an) == 0) {
                        len = ws_recv_frame(sock, buf, sizeof(buf) - 1);
                        if (len > 0) buf[len] = 0;
                    } else {
                        len = -1;
                    }
                }
            }
            if (len > 0) {
                if (strstr((const char *) buf, "\"WELCOME\"")) {
                    ESP_LOGI(TAG, "da dang ki cam bien %d voi server %s:%d", CONFIG_WS_SENSOR_ID,
                             CONFIG_WS_SERVER_HOST, CONFIG_WS_SERVER_TCP_PORT);
                    xTaskCreate(recv_task, "recv", 3072, (void *) (intptr_t) sock, 5, NULL);
                    return sock;
                }
                ESP_LOGE(TAG, "server tu choi: %s", (const char *) buf);   // DUPLICATE_SENSOR, AUTH_FAILED...
                if (strstr((const char *) buf, "AUTH_FAILED") || strstr((const char *) buf, "TOO_MANY_ATTEMPTS")) {
                    ESP_LOGE(TAG, "kiem tra WS_SENSOR_KEY trong menuconfig khop voi data/sensors.csv cua server");
                    wait_ms = 30000;                  // thử dồn dập sẽ bị server chặn theo IP
                }
            }
        }
        if (sock >= 0) close(sock);
        ESP_LOGW(TAG, "chua dang ki duoc voi server TCP %s:%d, thu lai sau %d giay", CONFIG_WS_SERVER_HOST,
                 CONFIG_WS_SERVER_TCP_PORT, wait_ms / 1000);
        vTaskDelay(pdMS_TO_TICKS(wait_ms));
    }
}
#endif

static void send_task(void *arg) {
#if CONFIG_WS_TRANSPORT_TCP
    int sock = tcp_register();
#else
    struct sockaddr_in dest;
    int sock = ws_udp_open_retry(CONFIG_WS_SERVER_HOST, CONFIG_WS_SERVER_PORT, &dest);
#endif
    uint8_t pkt[WS_MAX_PACKET];
    csi_item_t item;
    bool logged_len = false;
    uint32_t sent = 0, send_errors = 0;
    int8_t last_rssi = 0;
    int64_t last_status = esp_timer_get_time();
    for (;;) {
        if (xQueueReceive(s_queue, &item, pdMS_TO_TICKS(1000)) == pdTRUE) {
            if (!logged_len) {                      // khác 128 byte thì báo TV2: số sóng mang con đổi
                ESP_LOGI(TAG, "info->len = %u byte (%u song mang con)", item.len, item.len / 2);
                logged_len = true;
            }
            size_t len = ws_build_packet(pkt, CONFIG_WS_SENSOR_ID, item.seq, item.ts_us,
                                         item.rssi, item.buf, item.len / 2);
#if CONFIG_WS_TRANSPORT_TCP
            if (send_frame_locked(sock, pkt, len) != 0) {
                send_errors++;
                ESP_LOGW(TAG, "mat ket noi server, noi lai");
                shutdown(sock, SHUT_RDWR);           // làm recv_task của kết nối cũ thoát
                close(sock);
                sock = tcp_register();               // khung đến trong lúc nối lại bị bỏ ở hàng đợi (s_dropped)
            } else {
                sent++;
            }
#else
            if (sendto(sock, pkt, len, 0, (struct sockaddr *) &dest, sizeof(dest)) < 0) send_errors++;
            else sent++;
#endif
            last_rssi = item.rssi;
        }
        int64_t now = esp_timer_get_time();
        if (now - last_status >= STATUS_PERIOD_MS * 1000LL) {
            float sec = (now - last_status) / 1e6f;
            ESP_LOGI(TAG, "%.0f goi/giay, bo %lu (hang doi day), loi gui %lu, rssi %d, kenh %u",
                     sent / sec, (unsigned long) s_dropped, (unsigned long) send_errors, last_rssi,
                     ws_wifi_channel());
            if (sent == 0) ESP_LOGW(TAG, "khong co CSI: kiem tra nguon CSI trong menuconfig");
            sent = 0;
            send_errors = 0;
            s_dropped = 0;
            last_status = now;
        }
    }
}

void app_main(void) {
    s_queue = xQueueCreate(32, sizeof(csi_item_t));
    ws_wifi_connect(CONFIG_WS_WIFI_SSID, CONFIG_WS_WIFI_PASS);

    // Chỉ lấy LLTF: 64 sóng mang con, 128 byte, khớp với mọi router và với server
    wifi_csi_config_t cfg = {
        .lltf_en = true,
        .htltf_en = false,
        .stbc_htltf2_en = false,
        .ltf_merge_en = true,
        .channel_filter_en = true,
#if CONFIG_WS_SOURCE_ROUTER
        .manu_scale = true,                         // như csi_recv_router: tỉ lệ cố định, biên độ ổn định
        .shift = true,
#else
        .manu_scale = false,                        // như csi_recv
        .shift = 0,
#endif
    };

#if CONFIG_WS_SOURCE_ESPNOW
    unsigned int m[6];
    if (sscanf(CONFIG_WS_SENDER_MAC, "%x:%x:%x:%x:%x:%x", &m[0], &m[1], &m[2], &m[3], &m[4], &m[5]) != 6) {
        ESP_LOGE(TAG, "WS_SENDER_MAC sai dinh dang: %s", CONFIG_WS_SENDER_MAC);
        return;
    }
    for (int i = 0; i < 6; i++) s_sender_mac[i] = (uint8_t) m[i];
    ESP_ERROR_CHECK(esp_wifi_set_promiscuous(true));   // như csi_recv: nhận cả gói ESP-NOW không gửi cho mình
    ESP_LOGI(TAG, "nguon CSI: ESP-NOW tu %s; csi_send phai phat o kenh %u", CONFIG_WS_SENDER_MAC,
             ws_wifi_channel());
#else
    ESP_LOGI(TAG, "nguon CSI: router (kenh %u)", ws_wifi_channel());
#endif

    ESP_ERROR_CHECK(esp_wifi_set_csi_config(&cfg));
    ESP_ERROR_CHECK(esp_wifi_set_csi_rx_cb(csi_cb, NULL));
    ESP_ERROR_CHECK(esp_wifi_set_csi(true));
#if CONFIG_WS_TRANSPORT_TCP
    s_tx_lock = xSemaphoreCreateMutex();
#endif
    // Wi-Fi chạy ở lõi 0; đặt task gửi ở lõi còn lại nếu chip có 2 lõi
    xTaskCreatePinnedToCore(send_task, "send", 4096, NULL, 5, NULL, portNUM_PROCESSORS > 1 ? 1 : 0);
#if CONFIG_WS_SOURCE_ROUTER
    ping_gateway_set_hz(CONFIG_WS_PING_HZ);
#endif
#if CONFIG_WS_TRANSPORT_TCP
    ESP_LOGI(TAG, "gui CSI qua TCP toi %s:%d (client cam bien)", CONFIG_WS_SERVER_HOST, CONFIG_WS_SERVER_TCP_PORT);
#else
    ESP_LOGI(TAG, "gui CSI qua UDP toi %s:%d (du phong)", CONFIG_WS_SERVER_HOST, CONFIG_WS_SERVER_PORT);
#endif
}
