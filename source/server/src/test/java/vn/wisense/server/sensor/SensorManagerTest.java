package vn.wisense.server.sensor;

import org.junit.jupiter.api.Test;
import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.model.Event;
import vn.wisense.server.udp.PacketTracker;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SensorManagerTest {
    private static CsiFrame frame(int sensorId, long seq) {
        return new CsiFrame(sensorId, seq, seq * 10_000L, -40,
                new float[]{0.1f, 0.2f}, new float[]{0.0f, 0.1f});
    }

    @Test void throttlesSignalToAboutTenEventsPerHundredFrames() {
        List<String> jsonEvents = new CopyOnWriteArrayList<>();
        try (SensorManager manager = new SensorManager(event -> jsonEvents.add(event.toJson()))) {
            PacketTracker.Snapshot stats = new PacketTracker.Snapshot(100, 0.0, 100, 0, 0, 100);
            for (int i = 1; i <= 100; i++) manager.onFrame(frame(1, i), stats);
            long signals = jsonEvents.stream().filter(s -> s.contains("\"t\":\"SIGNAL\"")).count();
            assertEquals(10, signals);
        }
    }

    @Test void periodicallyPublishesStatsAndReportsZeroPpsAfterSensorGoesQuiet() throws Exception {
        List<String> jsonEvents = new CopyOnWriteArrayList<>();
        try (SensorManager manager = new SensorManager(event -> jsonEvents.add(event.toJson()))) {
            manager.onFrame(frame(7, 1), new PacketTracker.Snapshot(98, 0.02, 1, 0, 0, 1));
            TimeUnit.MILLISECONDS.sleep(1_250);
            assertTrue(jsonEvents.stream().anyMatch(s -> s.contains("\"t\":\"STATS\"")));
            assertTrue(jsonEvents.stream().anyMatch(s -> s.contains("\"t\":\"STATS\"")
                    && s.contains("\"pps\":0")), "expected periodic STATS with pps=0 after quiet period");
        }
    }
}
