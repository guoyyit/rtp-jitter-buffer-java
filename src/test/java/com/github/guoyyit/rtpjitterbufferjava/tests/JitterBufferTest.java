package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.BufferedPacket;
import com.github.guoyyit.rtpjitterbufferjava.buffer.DropPolicy;
import com.github.guoyyit.rtpjitterbufferjava.buffer.EnqueueResult;
import com.github.guoyyit.rtpjitterbufferjava.buffer.GlobalBufferBudget;
import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBuffer;
import com.github.guoyyit.rtpjitterbufferjava.model.RtpPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JitterBufferTest {

    private static BufferedPacket packet(long seq, long arrivalMs) {
        return new BufferedPacket(null, arrivalMs, seq);
    }

    @Test
    void reordersWithinWindowAndReleasesInSequenceOrder() {
        JitterBuffer buffer = new JitterBuffer(32, 16);

        assertEquals(EnqueueResult.ACCEPTED, buffer.enqueue(packet(1, 0),
                DropPolicy.DROP_NEWEST, null));
        assertEquals(EnqueueResult.ACCEPTED, buffer.enqueue(packet(3, 20),
                DropPolicy.DROP_NEWEST, null));
        assertEquals(EnqueueResult.ACCEPTED, buffer.enqueue(packet(2, 10),
                DropPolicy.DROP_NEWEST, null));

        List<BufferedPacket> due = buffer.pollDue(100, 0);

        assertEquals(3, due.size());
        assertEquals(1, due.get(0).extendedSequence());
        assertEquals(2, due.get(1).extendedSequence());
        assertEquals(3, due.get(2).extendedSequence());
    }

    @Test
    void keepsOnlyOneCopyOfDuplicate() {
        JitterBuffer buffer = new JitterBuffer(32, 16);

        buffer.enqueue(packet(5, 0), DropPolicy.DROP_NEWEST, null);
        assertEquals(EnqueueResult.DUPLICATE,
                buffer.enqueue(packet(5, 1), DropPolicy.DROP_NEWEST, null));

        assertEquals(1, buffer.size());
    }

    @Test
    void dropsLatePacketAfterPlayback() {
        JitterBuffer buffer = new JitterBuffer(32, 16);

        buffer.enqueue(packet(10, 0), DropPolicy.DROP_NEWEST, null);
        buffer.enqueue(packet(11, 10), DropPolicy.DROP_NEWEST, null);
        buffer.pollDue(100, 0);

        assertEquals(EnqueueResult.LATE_DROPPED,
                buffer.enqueue(packet(10, 20), DropPolicy.DROP_NEWEST, null));
    }

    @Test
    void dropsPacketsOutsideReorderWindow() {
        JitterBuffer buffer = new JitterBuffer(256, 16);

        buffer.enqueue(packet(50, 0), DropPolicy.DROP_NEWEST, null);
        buffer.enqueue(packet(40, 1), DropPolicy.DROP_NEWEST, null);
        buffer.enqueue(packet(33, 2), DropPolicy.DROP_NEWEST, null);

        assertEquals(EnqueueResult.LATE_DROPPED,
                buffer.enqueue(packet(32, 3), DropPolicy.DROP_NEWEST, null));
    }

    @Test
    void respectsPlayoutDelayBeforeReleasing() {
        JitterBuffer buffer = new JitterBuffer(32, 16);

        buffer.enqueue(packet(1, 0), DropPolicy.DROP_NEWEST, null);

        assertEquals(0, buffer.pollDue(49, 50).size());
        assertEquals(1, buffer.pollDue(50, 50).size());
    }

    @Test
    void rejectsNewestWhenSessionBufferFull() {
        JitterBuffer buffer = new JitterBuffer(2, 16);

        buffer.enqueue(packet(1, 0), DropPolicy.DROP_NEWEST, null);
        buffer.enqueue(packet(2, 0), DropPolicy.DROP_NEWEST, null);

        assertEquals(EnqueueResult.SESSION_FULL,
                buffer.enqueue(packet(3, 0), DropPolicy.DROP_NEWEST, null));
        assertEquals(2, buffer.size());
    }

    @Test
    void evictsOldestWhenConfigured() {
        JitterBuffer buffer = new JitterBuffer(2, 16);

        buffer.enqueue(packet(1, 0), DropPolicy.DROP_OLDEST, null);
        buffer.enqueue(packet(2, 0), DropPolicy.DROP_OLDEST, null);
        assertEquals(EnqueueResult.ACCEPTED,
                buffer.enqueue(packet(3, 0), DropPolicy.DROP_OLDEST, null));

        assertEquals(2, buffer.size());
        assertEquals(EnqueueResult.LATE_DROPPED,
                buffer.enqueue(packet(1, 1), DropPolicy.DROP_NEWEST, null));
    }

    @Test
    void globalBudgetCapsAcrossBuffers() {
        GlobalBufferBudget budget = new GlobalBufferBudget(2);
        JitterBuffer a = new JitterBuffer(8, 16);
        JitterBuffer b = new JitterBuffer(8, 16);

        assertEquals(EnqueueResult.ACCEPTED,
                a.enqueue(packet(1, 0), DropPolicy.DROP_NEWEST, budget));
        assertEquals(EnqueueResult.ACCEPTED,
                b.enqueue(packet(1, 0), DropPolicy.DROP_NEWEST, budget));
        assertEquals(EnqueueResult.GLOBAL_FULL,
                a.enqueue(packet(2, 0), DropPolicy.DROP_NEWEST, budget));

        assertEquals(2, budget.used());
        a.clear(budget);
        assertEquals(1, budget.used());
    }

    @Test
    void dropOldestReleasesAndReacquiresGlobalBudget() {
        GlobalBufferBudget budget = new GlobalBufferBudget(2);
        JitterBuffer buffer = new JitterBuffer(2, 16);

        buffer.enqueue(packet(1, 0), DropPolicy.DROP_OLDEST, budget);
        buffer.enqueue(packet(2, 0), DropPolicy.DROP_OLDEST, budget);
        assertEquals(EnqueueResult.ACCEPTED,
                buffer.enqueue(packet(3, 0), DropPolicy.DROP_OLDEST, budget));
        assertEquals(2, budget.used());
    }

    @Test
    void releasesGlobalBudgetWhenPacketsArePlayedOut() {
        GlobalBufferBudget budget = new GlobalBufferBudget(4);
        JitterBuffer buffer = new JitterBuffer(8, 16);

        buffer.enqueue(packet(1, 0), DropPolicy.DROP_NEWEST, budget);
        buffer.enqueue(packet(2, 10), DropPolicy.DROP_NEWEST, budget);
        assertEquals(2, budget.used());

        buffer.pollDue(100, 0, budget);

        assertEquals(0, budget.used());

        buffer.enqueue(packet(3, 20), DropPolicy.DROP_NEWEST, budget);
        buffer.enqueue(packet(4, 30), DropPolicy.DROP_NEWEST, budget);
        buffer.enqueue(packet(5, 40), DropPolicy.DROP_NEWEST, budget);
        buffer.enqueue(packet(6, 50), DropPolicy.DROP_NEWEST, budget);
        assertEquals(EnqueueResult.GLOBAL_FULL,
                buffer.enqueue(packet(7, 60), DropPolicy.DROP_NEWEST, budget));
    }
}
