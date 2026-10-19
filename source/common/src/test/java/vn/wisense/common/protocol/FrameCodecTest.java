package vn.wisense.common.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FrameCodecTest {
    private static DataInputStream input(byte[] b) {
        return new DataInputStream(new ByteArrayInputStream(b));
    }

    private static byte[] frame(int len, byte[] body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(len);
        out.write(body);
        return bytes.toByteArray();
    }

    @Test
    void ghiRoiDocLaiNhieuKhung() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        FrameCodec.writeFrame(out, "{\"t\":\"PING\"}");
        FrameCodec.writeFrame(out, "{\"t\":\"OK\",\"re\":\"SUBSCRIBE\"}");

        DataInputStream in = input(bytes.toByteArray());
        assertEquals("{\"t\":\"PING\"}", FrameCodec.readFrame(in));
        assertEquals("{\"t\":\"OK\",\"re\":\"SUBSCRIBE\"}", FrameCodec.readFrame(in));
    }

    @Test
    void doDaiTinhTheoByteUtf8KhongTheoKyTu() throws IOException {
        String json = "{\"t\":\"ERROR\",\"msg\":\"Cảm biến không tồn tại\"}";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FrameCodec.writeFrame(new DataOutputStream(bytes), json);

        byte[] raw = bytes.toByteArray();
        int len = new DataInputStream(new ByteArrayInputStream(raw)).readInt();
        assertEquals(json.getBytes(StandardCharsets.UTF_8).length, len);
        assertEquals(json, FrameCodec.readFrame(input(raw)));
    }

    @Test
    void doDaiSaiThiNemLoi() throws IOException {
        assertThrows(IOException.class, () -> FrameCodec.readFrame(input(frame(0, new byte[0]))));
        assertThrows(IOException.class, () -> FrameCodec.readFrame(input(frame(-5, new byte[0]))));
        assertThrows(IOException.class,
                () -> FrameCodec.readFrame(input(frame(FrameCodec.MAX_FRAME + 1, new byte[0]))));
    }

    @Test
    void dongGiuaChungThiNemEof() throws IOException {
        assertThrows(EOFException.class, () -> FrameCodec.readFrame(input(new byte[0])));
        assertThrows(EOFException.class, () -> FrameCodec.readFrame(input(frame(10, new byte[3]))));
    }

    @Test
    void khongGhiKhungRongHoacQuaLon() {
        DataOutputStream out = new DataOutputStream(new ByteArrayOutputStream());
        assertThrows(IOException.class, () -> FrameCodec.writeFrame(out, ""));
        assertThrows(IOException.class, () -> FrameCodec.writeFrame(out, "x".repeat(FrameCodec.MAX_FRAME + 1)));
    }
}
