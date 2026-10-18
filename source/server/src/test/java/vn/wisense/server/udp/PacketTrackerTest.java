package vn.wisense.server.udp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketTrackerTest {
    @Test void countsMissingPacketsAndRejectsOutOfOrder() {
        PacketTracker tracker = new PacketTracker();
        assertTrue(tracker.accept(10, 100));
        assertTrue(tracker.accept(12, 200));
        assertFalse(tracker.accept(11, 300));
        PacketTracker.Snapshot s = tracker.snapshot(400);
        assertEquals(1, s.missing());
        assertEquals(1, s.outOfOrder());
        assertEquals(2, s.accepted());
        assertEquals(1.0 / 3.0, s.loss(), 0.0001);
    }

    @Test void acceptsSequenceRestartAfterEsp32Reset() {
        PacketTracker tracker = new PacketTracker();
        assertTrue(tracker.accept(5_000, 100));
        assertTrue(tracker.accept(0, 200));
        assertTrue(tracker.accept(1, 300));
        PacketTracker.Snapshot snapshot = tracker.snapshot(400);
        assertEquals(1, snapshot.lastSeq());
        assertEquals(0, snapshot.missing());
        assertEquals(3, snapshot.accepted());
    }

    @Test void rejectsOrdinaryOutOfOrderPacket() {
        PacketTracker tracker = new PacketTracker();
        assertTrue(tracker.accept(100, 100));
        assertFalse(tracker.accept(99, 200));
        assertEquals(1, tracker.snapshot(300).outOfOrder());
    }

    @Test void handlesUnsignedSequenceWraparound() {
        PacketTracker tracker = new PacketTracker();
        assertTrue(tracker.accept(0xffff_ffffL, 100));
        assertTrue(tracker.accept(0, 200));
        assertEquals(0, tracker.snapshot(300).missing());
    }
}
