package vn.wisense.server.sensor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.wisense.common.model.Event;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EventLogTest {
    @TempDir Path tempDir;

    @Test
    void recordsSupportedEventsNewestFirstAndPerSensor() {
        EventLog log = new EventLog(null);
        log.record(Event.breath(1, 15.2, 4.1));
        log.record(Event.fall(2, 1234, 0.9));
        log.record(Event.breath(1, 16.0, 4.5));

        List<String> sensor1 = log.recent(1, 10);
        assertEquals(2, sensor1.size());
        assertTrue(sensor1.get(0).contains("\"bpm\":16.0"));
        assertTrue(sensor1.get(1).contains("\"bpm\":15.2"));
        assertEquals(1, log.recent(2, 10).size());
        assertTrue(log.recent(99, 10).isEmpty());
    }

    @Test
    void ignoresSignalStatsAndNullEvents() {
        EventLog log = new EventLog(null);
        log.record(Event.signal(1, new float[]{1, 2}));
        log.record(Event.stats(1, 100, 0.01));
        log.record(null);
        assertTrue(log.recent(1, 10).isEmpty());
    }

    @Test
    void motionOnlyRecordedWhenMovingStateChanges() {
        EventLog log = new EventLog(null);
        log.record(Event.motion(1, 0.1, false));
        log.record(Event.motion(1, 0.2, false));
        log.record(Event.motion(1, 0.8, true));
        log.record(Event.motion(1, 0.9, true));
        log.record(Event.motion(1, 0.1, false));

        List<String> history = log.recent(1, 10);
        assertEquals(3, history.size());
        assertTrue(history.get(0).contains("\"moving\":false"));
        assertTrue(history.get(1).contains("\"moving\":true"));
        assertTrue(history.get(2).contains("\"moving\":false"));
    }

    @Test
    void motionStateIsTrackedIndependentlyPerSensor() {
        EventLog log = new EventLog(null);
        log.record(Event.motion(1, 0.1, true));
        log.record(Event.motion(2, 0.1, true));
        log.record(Event.motion(1, 0.2, true));
        assertEquals(1, log.recent(1, 10).size());
        assertEquals(1, log.recent(2, 10).size());
    }

    @Test
    void enforcesHistoryLimitAndMaximumCapacity() {
        EventLog log = new EventLog(null);
        for (int i = 0; i < 510; i++) log.record(Event.breath(1, 10 + i, 2));
        assertEquals(500, log.recent(1, 500).size());
        assertTrue(log.recent(1, 1).get(0).contains("\"bpm\":519.0"));
        assertThrows(IllegalArgumentException.class, () -> log.recent(1, 0));
        assertThrows(IllegalArgumentException.class, () -> log.recent(1, 501));
    }

    @Test
    void persistsAcceptedEventsToCsvButNotDuplicateMotion() throws Exception {
        Path csv = tempDir.resolve("events.csv");
        EventLog log = new EventLog(csv);
        log.record(Event.motion(1, 0.1, true));
        log.record(Event.motion(1, 0.2, true)); // duplicate state: not persisted
        log.record(Event.breath(1, 15, 4));
        log.record(Event.stats(1, 100, 0.01));  // ignored

        String content = Files.readString(csv);
        assertEquals(3, content.lines().count()); // header + 2 accepted events
        assertTrue(content.contains(",MOTION,"));
        assertTrue(content.contains(",BREATH,"));
        assertFalse(content.contains(",STATS,"));
    }

    @Test
    void nullCsvPathStillKeepsHistory() {
        EventLog log = new EventLog(null);
        log.record(Event.fall(3, 100, 0.75));
        assertEquals(1, log.recent(3, 10).size());
    }
}
