package vn.wisense.common.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Đóng khung tin TCP theo hợp đồng chung: 4 byte độ dài (big-endian) rồi nội dung.
 * Nội dung bắt đầu bằng '{' là JSON UTF-8 (lệnh, sự kiện); bắt đầu bằng "CSI1" là gói CSI nhị phân
 * do client cảm biến gửi. Server và client cùng dùng lớp này để hai phía không lệch nhau.
 */
public final class FrameCodec {
    public static final int MAX_FRAME = 1 << 20;            // 1 MB, chặn độ dài rác

    private FrameCodec() {
    }

    /**
     * Đọc một khung dạng byte. Ném {@link java.io.EOFException} khi bên kia đóng kết nối,
     * ném {@link IOException} khi độ dài sai: luồng byte đã lệch nên bên gọi phải đóng kết nối.
     */
    public static byte[] readFrameBytes(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len <= 0 || len > MAX_FRAME) throw new IOException("Do dai khung sai: " + len);
        byte[] buf = new byte[len];
        in.readFully(buf);
        return buf;
    }

    /** Đọc một khung JSON. Lỗi như {@link #readFrameBytes}. */
    public static String readFrame(DataInputStream in) throws IOException {
        return new String(readFrameBytes(in), StandardCharsets.UTF_8);
    }

    /** Khung JSON (bắt đầu bằng '{') hay khung nhị phân (gói CSI1). */
    public static boolean isJson(byte[] frame) {
        return frame.length > 0 && frame[0] == '{';
    }

    /** Ghi độ dài, nội dung rồi flush. Nhiều luồng cùng ghi một socket thì bên gọi phải tự khoá. */
    public static void writeFrame(DataOutputStream out, byte[] payload) throws IOException {
        if (payload.length == 0 || payload.length > MAX_FRAME) throw new IOException("Do dai khung sai: " + payload.length);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    /** Ghi một khung JSON. */
    public static void writeFrame(DataOutputStream out, String json) throws IOException {
        writeFrame(out, json.getBytes(StandardCharsets.UTF_8));
    }
}
