package vn.wisense.common.model;

/** Hợp đồng chung: một khung CSI đã giải mã (TV2 tạo ra từ gói UDP). */
public record CsiFrame(int sensorId, long seq, long timestampUs, int rssi,
                       float[] amplitude, float[] phase) {}
