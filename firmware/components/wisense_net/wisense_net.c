#include <string.h>
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "lwip/netdb.h"
#include "nvs_flash.h"
#include "wisense_net.h"

#define GOT_IP_BIT BIT0
static EventGroupHandle_t s_events;

static void on_event(void *arg, esp_event_base_t base, int32_t id, void *data) {
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
        esp_wifi_connect();
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        xEventGroupClearBits(s_events, GOT_IP_BIT);
        esp_wifi_connect();                       // tự nối lại
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        xEventGroupSetBits(s_events, GOT_IP_BIT);
    }
}

void ws_wifi_connect(const char *ssid, const char *pass) {
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        nvs_flash_erase();
        nvs_flash_init();
    }
    s_events = xEventGroupCreate();
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_sta();
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, on_event, NULL);
    esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, on_event, NULL);

    wifi_config_t wc = {0};
    strncpy((char *) wc.sta.ssid, ssid, sizeof(wc.sta.ssid));
    strncpy((char *) wc.sta.password, pass, sizeof(wc.sta.password));
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wc));
    ESP_ERROR_CHECK(esp_wifi_start());
    esp_wifi_set_ps(WIFI_PS_NONE);                // tắt tiết kiệm pin để giữ đủ 100 gói/giây
    xEventGroupWaitBits(s_events, GOT_IP_BIT, pdFALSE, pdTRUE, portMAX_DELAY);
}

int ws_udp_open(const char *host, uint16_t port, struct sockaddr_in *dest) {
    struct addrinfo hints = {.ai_family = AF_INET, .ai_socktype = SOCK_DGRAM};
    struct addrinfo *res = NULL;
    if (getaddrinfo(host, NULL, &hints, &res) != 0 || res == NULL) return -1;
    *dest = *(struct sockaddr_in *) res->ai_addr;
    dest->sin_port = htons(port);
    freeaddrinfo(res);
    return socket(AF_INET, SOCK_DGRAM, IPPROTO_IP);
}

// ESP32 là little-endian nên tự ghi từng byte theo big-endian
static uint8_t *put_be(uint8_t *p, uint64_t v, int bytes) {
    for (int i = bytes - 1; i >= 0; i--) *p++ = (uint8_t) (v >> (8 * i));
    return p;
}

size_t ws_build_packet(uint8_t *out, uint8_t sensor_id, uint32_t seq,
                       uint64_t timestamp_us, int8_t rssi,
                       const int8_t *csi, uint16_t n) {
    if (n > WS_MAX_SUBCARRIERS) n = WS_MAX_SUBCARRIERS;
    uint8_t *p = out;
    memcpy(p, "CSI1", 4);
    p += 4;
    *p++ = sensor_id;
    p = put_be(p, seq, 4);
    p = put_be(p, timestamp_us, 8);
    *p++ = (uint8_t) rssi;
    p = put_be(p, n, 2);
    memcpy(p, csi, 2 * n);
    p += 2 * n;
    return p - out;
}
