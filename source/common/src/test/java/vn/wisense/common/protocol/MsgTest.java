package vn.wisense.common.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.wisense.common.model.Event;

class MsgTest {
    @Test
    void lenhClientDungHopDong() {
        assertEquals("{\"t\":\"HELLO\",\"ver\":1,\"name\":\"ui-1\"}", Msg.hello("ui-1"));
        assertEquals("{\"t\":\"SUBSCRIBE\",\"sensors\":[1,2],\"signal\":true}", Msg.subscribe(List.of(1, 2), true));
        assertEquals("{\"t\":\"GET_HISTORY\",\"sensor\":1,\"limit\":50}", Msg.getHistory(1, 50));
        assertEquals("{\"t\":\"SET_CONFIG\",\"sensor\":1,\"key\":\"motionThr\",\"value\":0.4}",
                Msg.setConfig(1, "motionThr", 0.4));
        assertEquals("{\"t\":\"ACK\",\"id\":42}", Msg.ack(42));
        assertEquals("{\"t\":\"PONG\"}", Msg.pong());
        assertEquals("{\"t\":\"BYE\"}", Msg.bye());
    }

    @Test
    void traLoiServerDungHopDong() {
        assertEquals("{\"t\":\"WELCOME\",\"session\":7,\"sensors\":[1]}", Msg.welcome(7, List.of(1)));
        assertEquals("{\"t\":\"OK\",\"re\":\"SUBSCRIBE\"}", Msg.ok(Msg.SUBSCRIBE));
        assertEquals("{\"t\":\"ERROR\",\"code\":\"BAD_ARG\",\"msg\":\"limit phải từ 1 đến 500\"}",
                Msg.error(Msg.BAD_ARG, "limit phải từ 1 đến 500"));
        assertEquals("{\"t\":\"PING\"}", Msg.ping());
    }

    @Test
    void historyNhungEventLaObjectKhongPhaiChuoi() {
        String json = Msg.history(1, List.of(Event.motion(1, 0.73, true).toJson(), Event.breath(1, 15.2, 4.1).toJson()));
        assertEquals("{\"t\":\"HISTORY\",\"sensor\":1,\"events\":["
                + "{\"t\":\"MOTION\",\"sensor\":1,\"level\":0.73,\"moving\":true},"
                + "{\"t\":\"BREATH\",\"sensor\":1,\"bpm\":15.2,\"snr\":4.1}]}", json);
        assertEquals("{\"t\":\"HISTORY\",\"sensor\":2,\"events\":[]}", Msg.history(2, List.of()));
    }

    @Test
    void chuoiCoDauNhayDuocEscape() {
        JsonObject o = Msg.parse(Msg.hello("ui \"a\""));
        assertEquals("ui \"a\"", o.get("name").getAsString());
    }

    @Test
    void parseTinHopLe() {
        JsonObject o = Msg.parse(Msg.subscribe(List.of(3), false));
        assertEquals(Msg.SUBSCRIBE, Msg.type(o));
        assertEquals(3, o.getAsJsonArray("sensors").get(0).getAsInt());
    }

    @Test
    void parseTinHongTraNull() {
        assertNull(Msg.parse("abc{"));
        assertNull(Msg.parse("[]"));
        assertNull(Msg.parse("42"));
        assertNull(Msg.parse("{}"));
        assertNull(Msg.parse("{\"t\":5}"));
        assertNull(Msg.parse("{\"t\":{\"x\":1}}"));
        assertNull(Msg.parse(""));
    }
}
