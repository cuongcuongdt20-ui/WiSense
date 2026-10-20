package vn.wisense.server.sensor;

import vn.wisense.common.model.Event;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded in-memory event history plus best-effort CSV persistence for GET_HISTORY. */
public final class EventLog {
    private static final int MAX_PER_SENSOR = 500;
    private final Map<Integer, Deque<String>> bySensor = new HashMap<>();
    private final Map<Integer, Boolean> lastMovingBySensor = new HashMap<>();
    private final Path csvPath;

    public EventLog(Path csvPath) { this.csvPath = csvPath; }

    /** Stores only useful history events; high-volume SIGNAL and periodic STATS are intentionally skipped. */
    public synchronized void record(Event event) {
        if (event == null || !(event.type().equals("MOTION") || event.type().equals("BREATH") || event.type().equals("FALL"))) return;
        if (event.type().equals("MOTION")) {
            Object movingValue = event.fields().get("moving");
            if (!(movingValue instanceof Boolean moving)) return;
            Boolean previousMoving = lastMovingBySensor.put(event.sensorId(), moving);
            if (previousMoving != null && previousMoving == moving) return;
        }
        String json = event.toJson();
        Deque<String> history = bySensor.computeIfAbsent(event.sensorId(), ignored -> new ArrayDeque<>());
        history.addFirst(json);
        while (history.size() > MAX_PER_SENSOR) history.removeLast();
        appendCsv(event, json);
    }

    /** Returns a defensive copy, newest first. */
    public synchronized List<String> recent(int sensor, int limit) {
        if (limit < 1 || limit > MAX_PER_SENSOR) throw new IllegalArgumentException("limit must be in range 1..500");
        Deque<String> history = bySensor.get(sensor);
        if (history == null) return List.of();
        List<String> result = new ArrayList<>(Math.min(limit, history.size()));
        for (String json : history) {
            if (result.size() >= limit) break;
            result.add(json);
        }
        return result;
    }

    private void appendCsv(Event event, String json) {
        if (csvPath == null) return;
        try {
            Path parent = csvPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            boolean newFile = !Files.exists(csvPath);
            String line = (newFile ? "ts,sensor,type,json\n" : "") + System.currentTimeMillis() + ","
                    + event.sensorId() + "," + event.type() + ",\"" + json.replace("\"", "\"\"") + "\"\n";
            Files.writeString(csvPath, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Could not append event log: " + e.getMessage());
        }
    }
}
