// Firmware thật: nhận CSI từ gói ESP-NOW của con phát (csi_send), gửi về server qua UDP.
// Con phát, con thu và router phải cùng một kênh Wi-Fi. Cấu hình bằng idf.py menuconfig → WiSense.
#include <stdbool.h>
#include <string.h>
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "wisense_net.h"

typedef struct {
    uint32_t seq;
    int64_t ts_us;
    int8_t rssi;
    uint16_t len;                                   // số byte trong buf = 2 × số sóng mang con
    int8_t buf[2 * WS_MAX_SUBCARRIERS];
} csi_item_t;

// MAC của con phát: lấy đúng giá trị đang dùng trong csi_send
static const uint8_t SENDER_MAC[6] = {0x1a, 0x00, 0x00, 0x00, 0x00, 0x00};
static const char *TAG = "wisense-csi";
static QueueHandle_t s_queue;
static uint32_t s_seq;                              // tăng trong callback để server thấy cả gói bị bỏ

static void csi_cb(void *ctx, wifi_csi_info_t *info) {
    if (!info || !info->buf || memcmp(info->mac, SENDER_MAC, 6) != 0) return;
    csi_item_t item;
    item.seq = s_seq++;
    item.ts_us = esp_timer_get_time();
    item.rssi = info->rx_ctrl.rssi;
    item.len = info->len > sizeof(item.buf) ? sizeof(item.buf) : info->len;
    memcpy(item.buf, info->buf, item.len);
    xQueueSend(s_queue, &item, 0);                  // đầy thì bỏ, không chặn task Wi-Fi
}

static void send_task(void *arg) {
    struct sockaddr_in dest;
    int sock = ws_udp_open(CONFIG_WS_SERVER_HOST, CONFIG_WS_SERVER_PORT, &dest);
    if (sock < 0) {
        ESP_LOGE(TAG, "khong mo duoc UDP toi %s", CONFIG_WS_SERVER_HOST);
        vTaskDelete(NULL);
    }
    uint8_t pkt[WS_MAX_PACKET];
    csi_item_t item;
    bool logged_len = false;
    for (;;) {
        if (xQueueReceive(s_queue, &item, portMAX_DELAY) != pdTRUE) continue;
        if (!logged_len) {                          // khác 128 byte thì báo TV2: số sóng mang con đổi
            ESP_LOGI(TAG, "info->len = %u byte (%u song mang con)", item.len, item.len / 2);
            logged_len = true;
        }
        size_t len = ws_build_packet(pkt, CONFIG_WS_SENSOR_ID, item.seq, item.ts_us,
                                     item.rssi, item.buf, item.len / 2);
        sendto(sock, pkt, len, 0, (struct sockaddr *) &dest, sizeof(dest));
    }
}

void app_main(void) {
    s_queue = xQueueCreate(32, sizeof(csi_item_t));
    ws_wifi_connect(CONFIG_WS_WIFI_SSID, CONFIG_WS_WIFI_PASS);

    wifi_csi_config_t cfg = {
        .lltf_en = true,                            // chỉ LLTF: 64 sóng mang con, 128 byte
        .htltf_en = false,
        .stbc_htltf2_en = false,
        .ltf_merge_en = true,
        .channel_filter_en = true,
        .manu_scale = false,
        .shift = 0,
    };
    ESP_ERROR_CHECK(esp_wifi_set_csi_config(&cfg));
    ESP_ERROR_CHECK(esp_wifi_set_csi_rx_cb(csi_cb, NULL));
    ESP_ERROR_CHECK(esp_wifi_set_csi(true));
    xTaskCreate(send_task, "send", 4096, NULL, 5, NULL);
    ESP_LOGI(TAG, "dang gui CSI toi %s:%d", CONFIG_WS_SERVER_HOST, CONFIG_WS_SERVER_PORT);
}
