package vn.wisense.client.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
        assertEquals(-1, f.id());                           // server cũ chưa gửi id
    }

    @Test
    void parsesFallWithId() {
        Message.Fall f = assertInstanceOf(Message.Fall.class,
                Message.parse("{\"t\":\"FALL\",\"sensor\":2,\"time\":5,\"confidence\":0.8,\"id\":42,\"ts\":1739000000123}"));
        assertEquals(42, f.id());
        assertEquals(2, f.sensor());
    }

    @Test
    void parsesWelcome() {
        Message.Welcome w = assertInstanceOf(Message.Welcome.class,
                Message.parse("{\"t\":\"WELCOME\",\"session\":7,\"sensors\":[1,3]}"));
        assertEquals(7, w.session());
        assertEquals(List.of(1, 3), w.sensors());
    }

    @Test
    void parsesOkAndError() {
        assertEquals("SUBSCRIBE", assertInstanceOf(Message.Ok.class,
                Message.parse("{\"t\":\"OK\",\"re\":\"SUBSCRIBE\"}")).re());
        Message.ServerError e = assertInstanceOf(Message.ServerError.class,
                Message.parse("{\"t\":\"ERROR\",\"code\":\"BAD_ARG\",\"msg\":\"limit sai\"}"));
        assertEquals("BAD_ARG", e.code());
        assertEquals("limit sai", e.msg());
    }

    @Test
    void parsesHistoryAndSkipsUnknownEvents() {
        Message.History h = assertInstanceOf(Message.History.class, Message.parse(
                "{\"t\":\"HISTORY\",\"sensor\":1,\"events\":["
                        + "{\"t\":\"FALL\",\"sensor\":1,\"time\":5,\"confidence\":0.8,\"id\":3},"
                        + "{\"t\":\"NEW\",\"sensor\":1},"
                        + "{\"t\":\"BREATH\",\"sensor\":1,\"bpm\":15.2,\"snr\":4.1}]}"));
        assertEquals(1, h.sensor());
        assertEquals(2, h.events().size());
        assertInstanceOf(Message.Fall.class, h.events().get(0));
        assertInstanceOf(Message.Breath.class, h.events().get(1));
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
