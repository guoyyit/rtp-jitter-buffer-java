package com.github.guoyyit.rtpjitterbufferjava.stats;

import com.github.guoyyit.rtpjitterbufferjava.codec.RejectReason;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class SessionCounters {

    private final Map<RejectReason, AtomicLong> rejected = new EnumMap<>(RejectReason.class);
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong lateDrops = new AtomicLong();
    private final AtomicLong sessionBufferFullDrops = new AtomicLong();
    private final AtomicLong globalBudgetDrops = new AtomicLong();
    private final AtomicLong collisions = new AtomicLong();
    private final AtomicLong invalidSequenceJumps = new AtomicLong();
    private final AtomicLong malformedRtcp = new AtomicLong();

    public void rejected(RejectReason reason) {
        rejected.computeIfAbsent(reason, ignored -> new AtomicLong()).incrementAndGet();
    }

    public long rejectedCount(RejectReason reason) {
        AtomicLong holder = rejected.get(reason);
        return holder == null ? 0 : holder.get();
    }

    public long totalRejected() {
        long total = 0;
        for (AtomicLong holder : rejected.values()) {
            total += holder.get();
        }
        return total;
    }

    public void duplicate() {
        duplicates.incrementAndGet();
    }

    public long duplicates() {
        return duplicates.get();
    }

    public void lateDrop() {
        lateDrops.incrementAndGet();
    }

    public long lateDrops() {
        return lateDrops.get();
    }

    public void sessionBufferFullDrop() {
        sessionBufferFullDrops.incrementAndGet();
    }

    public long sessionBufferFullDrops() {
        return sessionBufferFullDrops.get();
    }

    public void globalBudgetDrop() {
        globalBudgetDrops.incrementAndGet();
    }

    public long globalBudgetDrops() {
        return globalBudgetDrops.get();
    }

    public void collision() {
        collisions.incrementAndGet();
    }

    public long collisions() {
        return collisions.get();
    }

    public void invalidSequenceJump() {
        invalidSequenceJumps.incrementAndGet();
    }

    public long invalidSequenceJumps() {
        return invalidSequenceJumps.get();
    }

    public void malformedRtcp() {
        malformedRtcp.incrementAndGet();
    }

    public long malformedRtcpCount() {
        return malformedRtcp.get();
    }
}
