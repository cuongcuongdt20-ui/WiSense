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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Per-sensor rolling history and Detector dispatch. A frame history is capped at 60 seconds. */
public final class SensorManager implements AutoCloseable {
    private static final int DEFAULT_MAX_FRAMES = 6_000; // ~60 seconds at 100 packets/second
    private final Map<Integer, SensorState> sensors = new ConcurrentHashMap<>();
    private final List<Detector> detectors = new CopyOnWriteArrayList<>();
    private final Consumer<Event> eventSink;
    private final int maxFrames;
    private static final int SIGNAL_EVERY_N_FRAMES = 10;
    private final ScheduledExecutorService statsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wisense-stats-reporter");
        t.setDaemon(true);
        return t;
    });

    public SensorManager(Consumer<Event> eventSink) { this(eventSink, DEFAULT_MAX_FRAMES); }

    public SensorManager(Consumer<Event> eventSink, int maxFrames) {
        if (maxFrames < 1) throw new IllegalArgumentException("maxFrames must be positive");
        this.eventSink = eventSink == null ? ignored -> {} : eventSink;
        this.maxFrames = maxFrames;
        this.statsScheduler.scheduleAtFixedRate(this::publishAllSensorStats,
                1, 1, TimeUnit.SECONDS);
    }

    public void addDetector(Detector detector) {
        if (detector != null) detectors.add(detector);
    }

    /** Current implementation configures the shared detector instances for all sensors. */
    public void configure(int sensorId, String key, double value) {
        if (!sensors.containsKey(sensorId)) throw new IllegalArgumentException("Unknown sensor: " + sensorId);
        boolean supported = false;
        IllegalArgumentException lastError = null;
        for (Detector detector : detectors) {
            try {
                detector.configure(key, value);
                supported = true;
            } catch (IllegalArgumentException e) {
                // Detectors may support different keys; preserve the last validation error if none accepts it.
                lastError = e;
            }
        }
        if (!supported) {
            if (lastError != null) throw lastError;
            throw new IllegalArgumentException("Unknown configuration key: " + key);
        }
    }

    public void onFrame(CsiFrame frame, PacketTracker.Snapshot stats) {
        SensorState state = sensors.computeIfAbsent(frame.sensorId(), ignored -> new SensorState(maxFrames));
        synchronized (state) {
            state.frames.addLast(frame);
            while (state.frames.size() > maxFrames) state.frames.removeFirst();
            long now = System.nanoTime();
            state.lastFrameNanos = now;
            state.lastPps = stats.pps();
            state.lastLoss = stats.loss();
            state.signalFrameCount++;
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
        boolean publishSignal;
        synchronized (state) {
            publishSignal = state.signalFrameCount >= SIGNAL_EVERY_N_FRAMES;
            if (publishSignal) state.signalFrameCount = 0;
        }
        if (publishSignal) eventSink.accept(Event.signal(frame.sensorId(), frame.amplitude()));
    }

    private void publishAllSensorStats() {
        long now = System.nanoTime();
        for (Map.Entry<Integer, SensorState> entry : sensors.entrySet()) {
            SensorState state = entry.getValue();
            int pps;
            double loss;
            synchronized (state) {
                pps = state.lastFrameNanos == 0L || now - state.lastFrameNanos >= 1_000_000_000L
                        ? 0 : state.lastPps;
                loss = state.lastLoss;
            }
            try {
                eventSink.accept(Event.stats(entry.getKey(), pps, loss));
            } catch (RuntimeException e) {
                System.err.println("Could not publish stats for sensor " + entry.getKey() + ": " + e.getMessage());
            }
        }
    }

    /** Stops the periodic stats task; call when shutting down the server. */
    @Override public void close() {
        statsScheduler.shutdownNow();
    }

    public List<CsiFrame> recentFrames(int sensorId) {
        SensorState state = sensors.get(sensorId);
        if (state == null) return List.of();
        synchronized (state) { return new ArrayList<>(state.frames); }
    }

    public List<Integer> sensorIds() { return List.copyOf(sensors.keySet()); }

    private static final class SensorState {
        final Deque<CsiFrame> frames;
        long lastFrameNanos;
        int lastPps;
        double lastLoss;
        int signalFrameCount;
        SensorState(int maxFrames) { frames = new ArrayDeque<>(Math.min(maxFrames, 1024)); }
    }
}
