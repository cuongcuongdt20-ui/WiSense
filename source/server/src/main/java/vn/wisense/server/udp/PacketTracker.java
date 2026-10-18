package vn.wisense.server.udp;

import java.util.ArrayDeque;
import java.util.Deque;

/** Tracks packet ordering, estimated loss and packets-per-second for one sensor. */
public final class PacketTracker {
    private static final long SEQ_MODULUS = 1L << 32;
    private static final long SEQ_HALF_RANGE = 1L << 31;
    private final Deque<Long> acceptedNanos = new ArrayDeque<>();
    private long lastSeq = -1;
    private long accepted;
    private long missing;
    private long outOfOrder;

    public synchronized boolean accept(long seq, long nowNanos) {
        seq &= 0xffff_ffffL;
        if (lastSeq < 0) {
            lastSeq = seq;
            accepted++;
            acceptedNanos.addLast(nowNanos);
            trim(nowNanos);
            return true;
        }
        long delta = (seq - lastSeq + SEQ_MODULUS) % SEQ_MODULUS;
        if (delta == 0 || delta >= SEQ_HALF_RANGE) {
            outOfOrder++;
            return false;
        }
        if (delta > 1) missing += delta - 1;
        lastSeq = seq;
        accepted++;
        acceptedNanos.addLast(nowNanos);
        trim(nowNanos);
        return true;
    }

    public synchronized Snapshot snapshot(long nowNanos) {
        trim(nowNanos);
        long pps = acceptedNanos.size();
        double loss = (accepted + missing) == 0 ? 0.0 : (double) missing / (accepted + missing);
        return new Snapshot((int) Math.min(Integer.MAX_VALUE, pps), loss, accepted, missing, outOfOrder, lastSeq);
    }

    private void trim(long nowNanos) {
        long cutoff = nowNanos - 1_000_000_000L;
        while (!acceptedNanos.isEmpty() && acceptedNanos.peekFirst() < cutoff) acceptedNanos.removeFirst();
    }

    public record Snapshot(int pps, double loss, long accepted, long missing, long outOfOrder, long lastSeq) {}
}
