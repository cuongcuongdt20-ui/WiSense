package vn.wisense.client.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MessageTest {
    @Test
    void parsesMotion() {
        Message.Motion m = assertInstanceOf(Message.Motion.class,
                Message.parse("{\"t\":\"MOTION\",\"sensor\":1,\"level\":0.73,\"moving\":true}"));
        assertEquals(1, m.sensor());
        assertEquals(0.73, m.level(), 1e-9);
        assertTrue(m.moving());
    }

    @Test
    void parsesBreath() {
        Message.Breath b = assertInstanceOf(Message.Breath.class,
                Message.parse("{\"t\":\"BREATH\",\"sensor\":1,\"bpm\":15.2,\"snr\":4.1}"));
        assertEquals(15.2, b.bpm(), 1e-9);
        assertEquals(4.1, b.snr(), 1e-9);
    }

    @Test
    void parsesFall() {
        Message.Fall f = assertInstanceOf(Message.Fall.class,
                Message.parse("{\"t\":\"FALL\",\"sensor\":1,\"time\":1739000000,\"confidence\":0.8}"));
        assertEquals(1739000000L, f.time());
    }

    @Test
    void parsesStats() {
        Message.Stats s = assertInstanceOf(Message.Stats.class,
                Message.parse("{\"t\":\"STATS\",\"sensor\":1,\"pps\":98,\"loss\":0.02}"));
        assertEquals(98, s.pps());
        assertEquals(0.02, s.loss(), 1e-9);
    }

    @Test
    void parsesSignalArray() {
        Message.Signal s = assertInstanceOf(Message.Signal.class,
                Message.parse("{\"t\":\"SIGNAL\",\"sensor\":1,\"amp\":[1.5,2.5]}"));
        assertArrayEquals(new float[] {1.5f, 2.5f}, s.amp());
    }

    @Test
    void parsesPing() {
        assertInstanceOf(Message.Ping.class, Message.parse("{\"t\":\"PING\"}"));
    }

    @Test
    void unknownTypeIsIgnored() {
        assertNull(Message.parse("{\"t\":\"NEW\",\"sensor\":1}"));
    }
}
