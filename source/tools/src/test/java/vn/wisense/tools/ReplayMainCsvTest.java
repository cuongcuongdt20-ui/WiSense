package vn.wisense.tools;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

class ReplayMainCsvTest {
    @Test void csvRowCreatesExpectedCsi1Packet() throws Exception {
        Method method = ReplayMain.class.getDeclaredMethod("csvRowToPacket", String[].class);
        method.setAccessible(true);
        byte[] packet = (byte[]) method.invoke(null, (Object) new String[]{"1", "2", "3000", "-40", "3", "4", "-5", "12"});
        assertEquals(24, packet.length);
        assertEquals('C', packet[0]);
        assertEquals('S', packet[1]);
        assertEquals('I', packet[2]);
        assertEquals('1', packet[3]);
    }
}
