package vn.wisense.server.udp;

import org.junit.jupiter.api.Test;
import vn.wisense.common.model.CsiFrame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class CsiPacketDecoderTest {
    @Test void decodesHeaderAndImaginaryRealPairs() throws Exception {
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN);
        b.put(new byte[]{'C','S','I','1'}).put((byte) 7).putInt(12).putLong(123456).put((byte) -45)
                .putShort((short) 2).put((byte) 3).put((byte) 4).put((byte) -5).put((byte) 12);
        CsiFrame frame = CsiPacketDecoder.decode(b.array(), b.array().length);
        assertEquals(7, frame.sensorId());
        assertEquals(12, frame.seq());
        assertEquals(123456, frame.timestampUs());
        assertEquals(-45, frame.rssi());
        assertEquals(5.0f, frame.amplitude()[0], 0.0001f);
        assertEquals(13.0f, frame.amplitude()[1], 0.0001f);
        assertEquals(Math.atan2(3, 4), frame.phase()[0], 0.0001);
    }

    @Test void rejectsWrongMagicAndLength() {
        assertThrows(CsiPacketDecoder.PacketFormatException.class,
                () -> CsiPacketDecoder.decode(new byte[20], 20));
        byte[] shortPacket = new byte[]{'C','S','I','1', 1};
        assertThrows(CsiPacketDecoder.PacketFormatException.class,
                () -> CsiPacketDecoder.decode(shortPacket, shortPacket.length));
    }
}
