package vn.wisense.common.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Đổi gói CSI1 thành dòng CSV mà ReplayMain phát lại được:
 * sensorId,seq,timestampUs,rssi,imag0,real0,imag1,real1,...
 * Giữ nguyên cặp imag/real gốc nên phát lại cho kết quả giống hệt lúc ghi.
 * Dùng chung cho RecordMain (ghi qua UDP) và server (ghi khi cảm biến nối bằng TCP).
 */
public final class CsiCsv {
    public static final int HEADER_BYTES = 20;
    private static final byte[] MAGIC = {'C', 'S', 'I', '1'};

    private CsiCsv() {
    }

    /** Đổi một gói CSI1 thành dòng CSV; gói sai định dạng thì ném IllegalArgumentException. */
    public static String toRow(byte[] p, int length) {
        if (length < HEADER_BYTES) throw new IllegalArgumentException("Goi qua ngan: " + length);
        for (int i = 0; i < MAGIC.length; i++) {
            if (p[i] != MAGIC[i]) throw new IllegalArgumentException("Sai chu ky, khong phai CSI1");
        }
        ByteBuffer b = ByteBuffer.wrap(p, 0, length).order(ByteOrder.BIG_ENDIAN);
        b.position(4);
        int sensor = Byte.toUnsignedInt(b.get());
        long seq = Integer.toUnsignedLong(b.getInt());
        long ts = b.getLong();
        int rssi = b.get();
        int n = Short.toUnsignedInt(b.getShort());
        if (n < 1 || length != HEADER_BYTES + 2 * n) {
            throw new IllegalArgumentException("Do dai goi khong khop so song mang con: n=" + n + ", length=" + length);
        }
        StringBuilder sb = new StringBuilder(32 + 8 * n);
        sb.append(sensor).append(',').append(seq).append(',').append(ts).append(',').append(rssi);
        for (int i = 0; i < 2 * n; i++) sb.append(',').append((int) b.get());   // byte có dấu
        return sb.toString();
    }

    /** Dòng tiêu đề cho n sóng mang con, có xuống dòng. */
    public static String header(int n) {
        StringBuilder sb = new StringBuilder("sensorId,seq,timestampUs,rssi");
        for (int k = 0; k < n; k++) sb.append(",imag").append(k).append(",real").append(k);
        return sb.append('\n').toString();
    }

    /** Số sóng mang con của một dòng CSV đã tạo bằng {@link #toRow}. */
    public static int subcarriers(String row) {
        int commas = 0;
        for (int i = 0; i < row.length(); i++) if (row.charAt(i) == ',') commas++;
        return (commas + 1 - 4) / 2;
    }
}
