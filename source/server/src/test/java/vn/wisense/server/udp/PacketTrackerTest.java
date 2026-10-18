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

    @Test void handlesUnsignedSequenceWraparound() {
        PacketTracker tracker = new PacketTracker();
        assertTrue(tracker.accept(0xffff_ffffL, 100));
        assertTrue(tracker.accept(0, 200));
        assertEquals(0, tracker.snapshot(300).missing());
    }
}
