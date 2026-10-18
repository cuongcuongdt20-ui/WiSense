// Bản giả lập Wokwi: gửi 100 gói/giây với CSI tự sinh (nhịp thở 15 nhịp/phút, xung té ngã mỗi 30 giây).
// Gửi tới host.wokwi.internal cần Private Gateway (gói Wokwi trả phí).
#include <math.h>
#include "esp_log.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "wisense_net.h"

#define SENSOR_ID 1
#define N_SUB 64
#define SERVER_HOST "host.wokwi.internal"
#define SERVER_PORT 5005
static const char *TAG = "wisense-sim";

static void send_task(void *arg) {
    struct sockaddr_in dest;
    int sock = ws_udp_open(SERVER_HOST, SERVER_PORT, &dest);
    if (sock < 0) {
        ESP_LOGE(TAG, "khong mo duoc UDP");
        vTaskDelete(NULL);
    }
    int8_t csi[2 * N_SUB];
    uint8_t pkt[WS_MAX_PACKET];
    uint32_t seq = 0;
    TickType_t last = xTaskGetTickCount();
    for (;;) {
        int64_t now = esp_timer_get_time();
        float t = now / 1e6f;
        float breath = 0.5f * sinf(2 * M_PI * 0.25f * t);          // 15 nhịp/phút
        float fall = (fmodf(t, 30.0f) < 0.5f) ? 8.0f : 0.0f;          // xung té ngã mỗi 30 giây
        for (int k = 0; k < N_SUB; k++) {
            float amp = 20 + breath + fall + ((esp_random() % 100) / 100.0f - 0.5f);
            float ph = 0.1f * k;
            csi[2 * k] = (int8_t) (amp * sinf(ph));                   // imag
            csi[2 * k + 1] = (int8_t) (amp * cosf(ph));               // real
        }
        size_t len = ws_build_packet(pkt, SENSOR_ID, seq++, now, -40, csi, N_SUB);
        sendto(sock, pkt, len, 0, (struct sockaddr *) &dest, sizeof(dest));
        if (seq % 100 == 0) ESP_LOGI(TAG, "da gui %lu goi", (unsigned long) seq);
        xTaskDelayUntil(&last, pdMS_TO_TICKS(10));                  // 10 ms một gói
    }
}

void app_main(void) {
    ws_wifi_connect("Wokwi-GUEST", "");
    xTaskCreate(send_task, "send", 4096, NULL, 5, NULL);
}
