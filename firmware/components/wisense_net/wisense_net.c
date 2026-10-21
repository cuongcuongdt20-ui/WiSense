#include <string.h>
#include <unistd.h>
#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/task.h"
#include "lwip/netdb.h"
#include "nvs_flash.h"
#include "wisense_net.h"

#define GOT_IP_BIT BIT0
static const char *TAG = "wisense-net";
static EventGroupHandle_t s_events;
static uint8_t s_bssid[6];
static uint8_t s_channel;

static void on_event(void *arg, esp_event_base_t base, int32_t id, void *data) {
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
        esp_wifi_connect();
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        wifi_event_sta_disconnected_t *d = data;
        ESP_LOGW(TAG, "mat ket noi Wi-Fi (ly do %d), noi lai", d->reason);
        xEventGroupClearBits(s_events, GOT_IP_BIT);
        esp_wifi_connect();                       // tự nối lại
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        wifi_ap_record_t ap;
        if (esp_wifi_sta_get_ap_info(&ap) == ESP_OK) {   // router có thể đổi khi nối lại (mesh)
            memcpy(s_bssid, ap.bssid, 6);
            s_channel = ap.primary;
        }
        ip_event_got_ip_t *e = data;
        ESP_LOGI(TAG, "co IP " IPSTR ", kenh router %u", IP2STR(&e->ip_info.ip), s_channel);
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
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));   // như esp-csi: không ghi cấu hình vào flash
    esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, on_event, NULL);
    esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, on_event, NULL);

    // SSID/mật khẩu được dài đủ 32/64 byte và không cần ký tự kết thúc; phần thừa đã là 0 nhờ {0}
    wifi_config_t wc = {0};
    memcpy(wc.sta.ssid, ssid, strnlen(ssid, sizeof(wc.sta.ssid)));
    memcpy(wc.sta.password, pass, strnlen(pass, sizeof(wc.sta.password)));
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wc));
    ESP_ERROR_CHECK(esp_wifi_start());
    esp_wifi_set_ps(WIFI_PS_NONE);                // tắt tiết kiệm pin để giữ đủ 100 gói/giây
    xEventGroupWaitBits(s_events, GOT_IP_BIT, pdFALSE, pdTRUE, portMAX_DELAY);
}

const uint8_t *ws_wifi_bssid(void) {
    return s_bssid;
}

uint8_t ws_wifi_channel(void) {
    return s_channel;
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

int ws_udp_open_retry(const char *host, uint16_t port, struct sockaddr_in *dest) {
    for (;;) {
        int sock = ws_udp_open(host, port, dest);
        if (sock >= 0) return sock;
        ESP_LOGW(TAG, "khong mo duoc UDP toi %s:%u, thu lai sau 1 giay", host, port);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

int ws_tcp_connect(const char *host, uint16_t port) {
    struct addrinfo hints = {.ai_family = AF_INET, .ai_socktype = SOCK_STREAM};
    struct addrinfo *res = NULL;
    if (getaddrinfo(host, NULL, &hints, &res) != 0 || res == NULL) return -1;
    struct sockaddr_in dest = *(struct sockaddr_in *) res->ai_addr;
    dest.sin_port = htons(port);
    freeaddrinfo(res);
    int sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock < 0) return -1;
    if (connect(sock, (struct sockaddr *) &dest, sizeof(dest)) != 0) {
        close(sock);
        return -1;
    }
    int one = 1;
    setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));   // khung CSI nhỏ, gửi ngay không gom
    return sock;
}

// send/recv trên TCP có thể chỉ xong một phần: lặp tới khi đủ
static int send_all(int sock, const uint8_t *p, size_t n) {
    while (n > 0) {
        int w = send(sock, p, n, 0);
        if (w <= 0) return -1;
        p += w;
        n -= (size_t) w;
    }
    return 0;
}

static int recv_all(int sock, uint8_t *p, size_t n) {
    while (n > 0) {
        int r = recv(sock, p, n, 0);
        if (r <= 0) return -1;                    // 0 = server đóng kết nối
        p += r;
        n -= (size_t) r;
    }
    return 0;
}

int ws_send_frame(int sock, const void *data, size_t len) {
    uint8_t head[4] = {(uint8_t) (len >> 24), (uint8_t) (len >> 16), (uint8_t) (len >> 8), (uint8_t) len};
    if (send_all(sock, head, 4) != 0) return -1;
    return send_all(sock, (const uint8_t *) data, len);
}

int ws_recv_frame(int sock, uint8_t *buf, size_t cap) {
    uint8_t head[4];
    if (recv_all(sock, head, 4) != 0) return -1;
    uint32_t len = ((uint32_t) head[0] << 24) | ((uint32_t) head[1] << 16) | ((uint32_t) head[2] << 8) | head[3];
    if (len == 0 || len > cap) return -1;        // khung quá dài: luồng byte không còn tin được, đóng kết nối
    if (recv_all(sock, buf, len) != 0) return -1;
    return (int) len;
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
