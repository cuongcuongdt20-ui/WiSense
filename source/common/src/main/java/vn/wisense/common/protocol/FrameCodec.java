package vn.wisense.common.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Đóng khung tin TCP theo hợp đồng chung: 4 byte độ dài (big-endian) rồi JSON UTF-8.
 * Server và client cùng dùng lớp này để hai phía không lệch nhau.
 */
public final class FrameCodec {
    public static final int MAX_FRAME = 1 << 20;            // 1 MB, chặn độ dài rác

    private FrameCodec() {
    }

    /**
     * Đọc một khung. Ném {@link java.io.EOFException} khi bên kia đóng kết nối,
     * ném {@link IOException} khi độ dài sai: luồng byte đã lệch nên bên gọi phải đóng kết nối.
     */
    public static String readFrame(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len <= 0 || len > MAX_FRAME) throw new IOException("Do dai khung sai: " + len);
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    /** Ghi độ dài, nội dung rồi flush. Nhiều luồng cùng ghi một socket thì bên gọi phải tự khoá. */
    public static void writeFrame(DataOutputStream out, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        if (b.length == 0 || b.length > MAX_FRAME) throw new IOException("Do dai khung sai: " + b.length);
        out.writeInt(b.length);
        out.write(b);
        out.flush();
    }
}
