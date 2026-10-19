// Firmware thật cho ESP32-S3: lấy CSI rồi gửi về server qua UDP theo hợp đồng "CSI1".
// Cấu hình bằng idf.py menuconfig → WiSense. Có hai nguồn CSI:
//  - ROUTER (mặc định, chỉ cần 1 board): theo ví dụ csi_recv_router của esp-csi. Board nối router,
//    ping gateway WS_PING_HZ lần/giây; CSI lấy từ gói trả lời của router (lọc theo BSSID).
//  - ESPNOW (2 board): theo ví dụ csi_recv của esp-csi. Nhận gói ESP-NOW của con phát chạy csi_send
//    (lọc theo MAC). Con phát phải phát đúng kênh của router: xem dòng log "kenh router".
#include <math.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
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

static void send_task(void *arg) {
    struct sockaddr_in dest;
    int sock = ws_udp_open_retry(CONFIG_WS_SERVER_HOST, CONFIG_WS_SERVER_PORT, &dest);
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
            if (sendto(sock, pkt, len, 0, (struct sockaddr *) &dest, sizeof(dest)) < 0) send_errors++;
            else sent++;
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

#if CONFIG_WS_SOURCE_ROUTER
// Tạo lưu lượng đều để router trả lời liên tục, mỗi gói trả lời cho một khung CSI (như csi_recv_router)
static void ping_gateway_start(void) {
    esp_netif_ip_info_t ip;
    esp_netif_get_ip_info(esp_netif_get_handle_from_ifkey("WIFI_STA_DEF"), &ip);
    esp_ping_config_t pc = ESP_PING_DEFAULT_CONFIG();
    pc.count = ESP_PING_COUNT_INFINITE;
    pc.interval_ms = 1000 / CONFIG_WS_PING_HZ;
    pc.task_stack_size = 3072;
    pc.data_size = 1;
    pc.target_addr.u_addr.ip4.addr = ip4_addr_get_u32(&ip.gw);
    pc.target_addr.type = ESP_IPADDR_TYPE_V4;
    esp_ping_callbacks_t cbs = {0};
    esp_ping_handle_t handle;
    ESP_ERROR_CHECK(esp_ping_new_session(&pc, &cbs, &handle));
    ESP_ERROR_CHECK(esp_ping_start(handle));
    ESP_LOGI(TAG, "ping gateway " IPSTR " %d lan/giay", IP2STR(&ip.gw), CONFIG_WS_PING_HZ);
}
#endif

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
    // Wi-Fi chạy ở lõi 0; đặt task gửi ở lõi còn lại nếu chip có 2 lõi
    xTaskCreatePinnedToCore(send_task, "send", 4096, NULL, 5, NULL, portNUM_PROCESSORS > 1 ? 1 : 0);
#if CONFIG_WS_SOURCE_ROUTER
    ping_gateway_start();
#endif
    ESP_LOGI(TAG, "dang gui CSI toi %s:%d", CONFIG_WS_SERVER_HOST, CONFIG_WS_SERVER_PORT);
}
