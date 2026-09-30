package com.github.guoyyit.rtpjitterbufferjava.buffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class JitterBuffer {

    private final int maxPackets;
    private final int maxReorderWindow;

    private final TreeMap<Long, BufferedPacket> packets = new TreeMap<>();
    private long lastReleasedSequence = Long.MIN_VALUE;
    private long highestInsertedSequence = Long.MIN_VALUE;

    public JitterBuffer(int maxPackets, int maxReorderWindow) {
        if (maxPackets <= 0) {
            throw new IllegalArgumentException("maxPackets must be positive");
        }
        if (maxReorderWindow <= 0) {
            throw new IllegalArgumentException("maxReorderWindow must be positive");
        }
        this.maxPackets = maxPackets;
        this.maxReorderWindow = maxReorderWindow;
    }

    /**
     * Adds a packet to the reordering window.
     *
     * @param packet packet carrying its extended (never wrapping) sequence number
     * @param dropPolicy policy to apply when the per-session depth limit is reached
     * @param globalBudget shared cross-session budget; may be null
     * @return why the packet was accepted or dropped
     */
    public synchronized EnqueueResult enqueue(BufferedPacket packet, DropPolicy dropPolicy,
                                              BufferBudget globalBudget) {
        long seq = packet.extendedSequence();

        if (lastReleasedSequence != Long.MIN_VALUE && seq <= lastReleasedSequence) {
            return EnqueueResult.LATE_DROPPED;
        }
        if (packets.containsKey(seq)) {
            return EnqueueResult.DUPLICATE;
        }
        if (highestInsertedSequence != Long.MIN_VALUE
                && highestInsertedSequence - seq > maxReorderWindow) {
            return EnqueueResult.LATE_DROPPED;
        }

        if (packets.size() >= maxPackets) {
            return applyDropPolicy(dropPolicy, packet, globalBudget);
        }
        if (globalBudget != null && !globalBudget.tryAcquire()) {
            return EnqueueResult.GLOBAL_FULL;
        }

        return store(seq, packet);
    }

    private EnqueueResult applyDropPolicy(DropPolicy dropPolicy, BufferedPacket packet,
                                          BufferBudget globalBudget) {
        if (dropPolicy == DropPolicy.DROP_OLDEST) {
            Map.Entry<Long, BufferedPacket> oldest = packets.firstEntry();
            packets.remove(oldest.getKey());
            lastReleasedSequence = Math.max(lastReleasedSequence, oldest.getKey());
            if (globalBudget != null) {
                globalBudget.release();
            }
            if (globalBudget != null && !globalBudget.tryAcquire()) {
                return EnqueueResult.GLOBAL_FULL;
            }
            return store(packet.extendedSequence(), packet);
        }
        return EnqueueResult.SESSION_FULL;
    }

    private EnqueueResult store(long seq, BufferedPacket packet) {
        packets.put(seq, packet);
        if (seq > highestInsertedSequence) {
            highestInsertedSequence = seq;
        }
        return EnqueueResult.ACCEPTED;
    }

    /**
     * Releases all buffered packets whose scheduled playout time has arrived,
     * in extended sequence order. A packet becomes due once it is at the head
     * of the buffer and its arrival time plus playout delay is in the past.
     */
    public synchronized List<BufferedPacket> pollDue(long nowMillis, long playoutDelayMs) {
        return pollDue(nowMillis, playoutDelayMs, null);
    }

    /**
     * As {@link #pollDue(long, long)} but releases each played-out packet's slot
     * in the shared global budget.
     */
    public synchronized List<BufferedPacket> pollDue(long nowMillis, long playoutDelayMs,
                                                     BufferBudget globalBudget) {
        List<BufferedPacket> due = new ArrayList<>();
        while (!packets.isEmpty()) {
            Map.Entry<Long, BufferedPacket> head = packets.firstEntry();
            BufferedPacket packet = head.getValue();
            long scheduled = packet.arrivalMillis() + playoutDelayMs;
            if (scheduled > nowMillis) {
                break;
            }
            packets.remove(head.getKey());
            lastReleasedSequence = head.getKey();
            if (globalBudget != null) {
                globalBudget.release();
            }
            due.add(packet);
        }
        return due;
    }

    public synchronized int size() {
        return packets.size();
    }

    /**
     * Removes a previously admitted packet and releases its global budget slot.
     * Used to roll back enqueue when sequence validation rejects the packet.
     */
    public synchronized void remove(long sequence, BufferBudget globalBudget) {
        if (packets.remove(sequence) != null && globalBudget != null) {
            globalBudget.release();
        }
    }

    public synchronized long lastReleasedSequence() {
        return lastReleasedSequence;
    }

    public synchronized void clear(BufferBudget globalBudget) {
        int held = packets.size();
        packets.clear();
        if (globalBudget != null) {
            for (int i = 0; i < held; i++) {
                globalBudget.release();
            }
        }
    }
}
