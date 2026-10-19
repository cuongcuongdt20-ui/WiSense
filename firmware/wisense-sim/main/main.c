// Bản giả lập Wokwi: gửi 100 gói/giây với CSI tự sinh, theo kịch bản lặp lại mỗi 60 giây:
//    0–20 s  phòng trống
//   20–32 s  có người đi lại
//   32–40 s  người ngồi yên, thở 15 nhịp/phút
//   40 s     té ngã (biến động mạnh 0,5 giây)
//   40–60 s  nằm yên trên sàn, vẫn thở
// Mô hình tín hiệu giống FallDetectorTest (TV4): mỗi sóng mang con có biên độ nền riêng, chuyển động làm
// các sóng mang con đổi theo hướng khác nhau (trung bình gần như không đổi), sóng mang con rỗng bằng 0
// như LLTF thật. Nhờ vậy detector thấy được cả té ngã lẫn đi lại.
// Gửi tới host.wokwi.internal cần Private Gateway (gói Wokwi trả phí).
#include <math.h>
#include <stdbool.h>
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
#define CYCLE_S 60.0

// Kịch bản (giây trong chu kỳ) và độ lớn, tính theo tỉ lệ so với biên độ nền
#define WALK_START 20.0
#define WALK_END 32.0
#define WALK_AMP 0.3                        // đi lại: ±30 %, 2 Hz
#define WALK_HZ 2.0
#define BREATH_START 32.0
#define BREATH_AMP 0.02                     // thở: ±2 %, 0,25 Hz = 15 nhịp/phút
#define BREATH_HZ 0.25
#define FALL_START 40.0
#define FALL_LEN 0.5
#define FALL_AMP 0.4                        // té ngã: ±40 % trong 0,5 giây
#define NOISE 0.5                           // nhiễu cộng, biên độ tuyệt đối (đỉnh–đỉnh)

static const char *TAG = "wisense-sim";

static float s_profile[N_SUB];              // biên độ nền riêng từng sóng mang con
static float s_pattern[N_SUB];              // mẫu chuyển động: trung bình 0, |trung bình| = 1
static float s_breath[N_SUB];               // mẫu nhịp thở
static float s_phase[N_SUB];                // pha riêng khi đi lại
static float s_iq_phase[N_SUB];             // pha của CSI (để tách imag/real)

// Sóng mang con rỗng của LLTF (thứ tự 0..31, -32..-1): DC và dải bảo vệ ±27..±32
static bool is_null(int k) {
    return k == 0 || (k >= 27 && k <= 37);
}

// Số giả ngẫu nhiên cố định (LCG) để mẫu giống nhau mỗi lần chạy
static uint32_t s_lcg = 7;
static float lcg_uniform(void) {
    s_lcg = s_lcg * 1664525u + 1013904223u;
    return (s_lcg >> 8) / 16777216.0f;      // [0, 1)
}

static void init_model(void) {
    float sum = 0, abs_sum = 0;
    for (int k = 0; k < N_SUB; k++) {
        s_profile[k] = 20.0f * (1.0f + 0.4f * sinf(0.37f * k));
        s_pattern[k] = lcg_uniform() * 2 - 1;
        s_breath[k] = lcg_uniform() * 2 - 1;
        s_phase[k] = lcg_uniform() * 2 * (float) M_PI;
        s_iq_phase[k] = 0.1f * k;
        sum += s_pattern[k];
    }
    for (int k = 0; k < N_SUB; k++) {
        s_pattern[k] -= sum / N_SUB;
        abs_sum += fabsf(s_pattern[k]);
    }
    for (int k = 0; k < N_SUB; k++) s_pattern[k] /= abs_sum / N_SUB;
}

// Thay đổi tương đối của sóng mang con k tại thời điểm t (giây trong chu kỳ)
static float motion(double t, int k) {
    float m = 0;
    if (t >= WALK_START && t < WALK_END)
        m += WALK_AMP * sinf(2 * (float) M_PI * WALK_HZ * (float) t + s_phase[k]);
    if (t >= BREATH_START)
        m += BREATH_AMP * s_breath[k] * sinf(2 * (float) M_PI * BREATH_HZ * (float) t);
    if (t >= FALL_START && t < FALL_START + FALL_LEN)
        m += FALL_AMP * s_pattern[k];
    return m;
}

static const char *phase_name(double t) {
    if (t < WALK_START) return "phong trong";
    if (t < WALK_END) return "di lai";
    if (t < FALL_START) return "ngoi yen, tho";
    if (t < FALL_START + FALL_LEN) return "TE NGA";
    return "nam yen, tho";
}

static void send_task(void *arg) {
    struct sockaddr_in dest;
    int sock = ws_udp_open_retry(SERVER_HOST, SERVER_PORT, &dest);
    int8_t csi[2 * N_SUB];
    uint8_t pkt[WS_MAX_PACKET];
    uint32_t seq = 0;                       // khởi động lại thì về 0, giống board thật
    const char *last_phase = NULL;
    int64_t start = esp_timer_get_time();
    TickType_t last = xTaskGetTickCount();
    for (;;) {
        int64_t now = esp_timer_get_time();
        double t = fmod((now - start) / 1e6, CYCLE_S);   // double: float mất độ chính xác sau vài giờ
        const char *ph = phase_name(t);
        if (ph != last_phase) {
            ESP_LOGI(TAG, "t=%.0fs: %s", t, ph);
            last_phase = ph;
        }
        for (int k = 0; k < N_SUB; k++) {
            float amp = 0;
            if (!is_null(k)) {
                float noise = ((esp_random() % 1000) / 1000.0f - 0.5f) * NOISE;
                float rel = 1.0f + motion(t, k);
                amp = s_profile[k] * (rel > 0 ? rel : 0) + noise;
            }
            csi[2 * k] = ws_clamp_i8((int) lroundf(amp * sinf(s_iq_phase[k])));       // imag
            csi[2 * k + 1] = ws_clamp_i8((int) lroundf(amp * cosf(s_iq_phase[k])));   // real
        }
        size_t len = ws_build_packet(pkt, SENSOR_ID, seq++, now, -40, csi, N_SUB);
        sendto(sock, pkt, len, 0, (struct sockaddr *) &dest, sizeof(dest));
        if (seq % 1000 == 0) ESP_LOGI(TAG, "da gui %lu goi", (unsigned long) seq);
        xTaskDelayUntil(&last, pdMS_TO_TICKS(10));                  // 10 ms một gói
    }
}

void app_main(void) {
    init_model();
    ws_wifi_connect("Wokwi-GUEST", "");
    xTaskCreate(send_task, "send", 4096, NULL, 5, NULL);
}
