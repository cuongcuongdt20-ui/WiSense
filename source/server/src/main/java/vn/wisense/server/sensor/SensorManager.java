package vn.wisense.server.sensor;

import vn.wisense.common.model.CsiFrame;
import vn.wisense.common.model.Detector;
import vn.wisense.common.model.Event;
import vn.wisense.server.udp.PacketTracker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Per-sensor rolling history and Detector dispatch. A frame history is capped at 60 seconds. */
public final class SensorManager {
    private static final int DEFAULT_MAX_FRAMES = 6_000; // ~60 seconds at 100 packets/second
    private final Map<Integer, SensorState> sensors = new ConcurrentHashMap<>();
    private final List<Detector> detectors = new CopyOnWriteArrayList<>();
    private final Consumer<Event> eventSink;
    private final int maxFrames;

    public SensorManager(Consumer<Event> eventSink) { this(eventSink, DEFAULT_MAX_FRAMES); }

    public SensorManager(Consumer<Event> eventSink, int maxFrames) {
        if (maxFrames < 1) throw new IllegalArgumentException("maxFrames must be positive");
        this.eventSink = eventSink == null ? ignored -> {} : eventSink;
        this.maxFrames = maxFrames;
    }

    public void addDetector(Detector detector) {
        if (detector != null) detectors.add(detector);
    }

    public void onFrame(CsiFrame frame, PacketTracker.Snapshot stats) {
        SensorState state = sensors.computeIfAbsent(frame.sensorId(), ignored -> new SensorState(maxFrames));
        synchronized (state) {
            state.frames.addLast(frame);
            while (state.frames.size() > maxFrames) state.frames.removeFirst();
            long now = System.nanoTime();
            if (now - state.lastStatsNanos >= 1_000_000_000L) {
                state.lastStatsNanos = now;
                eventSink.accept(Event.stats(frame.sensorId(), stats.pps(), stats.loss()));
            }
        }

        for (Detector detector : detectors) {
            try {
                detector.onFrame(frame);
                List<Event> events = detector.poll();
                if (events != null) events.forEach(eventSink);
            } catch (RuntimeException e) {
                System.err.printf("Detector %s failed for sensor %d: %s%n",
                        detector.getClass().getSimpleName(), frame.sensorId(), e.getMessage());
            }
        }
        eventSink.accept(Event.signal(frame.sensorId(), frame.amplitude()));
    }

    public List<CsiFrame> recentFrames(int sensorId) {
        SensorState state = sensors.get(sensorId);
        if (state == null) return List.of();
        synchronized (state) { return new ArrayList<>(state.frames); }
    }

    public List<Integer> sensorIds() { return List.copyOf(sensors.keySet()); }

    private static final class SensorState {
        final Deque<CsiFrame> frames;
        long lastStatsNanos;
        SensorState(int maxFrames) { frames = new ArrayDeque<>(Math.min(maxFrames, 1024)); }
    }
}
