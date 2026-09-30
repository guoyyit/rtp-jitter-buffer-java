package com.github.guoyyit.rtpjitterbufferjava.stats;

import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpRejectReason;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Atomic counters describing everything observed on one SSRC or one session.
 */
public class ReceiveCounters {

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong played = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong lateDropped = new AtomicLong();
    private final AtomicLong overloadDropped = new AtomicLong();
    private final Map<RtpRejectReason, AtomicLong> rejected = new EnumMap<>(RtpRejectReason.class);

    private volatile long extendedMax;
    private volatile int cycles;
    private volatile long baseSeq = -1;

    public ReceiveCounters() {
        for (RtpRejectReason reason : RtpRejectReason.values()) {
            rejected.put(reason, new AtomicLong());
        }
    }

    public void recordReceived() {
        received.incrementAndGet();
    }

    public void recordRejected(RtpRejectReason reason) {
        rejected.get(reason).incrementAndGet();
    }

    public void recordDuplicate() {
        duplicates.incrementAndGet();
    }

    public void recordLateDropped() {
        lateDropped.incrementAndGet();
    }

    public void recordOverloadDropped() {
        overloadDropped.incrementAndGet();
    }

    public void recordPlayed() {
        played.incrementAndGet();
    }

    /**
     * Updates the RFC 3550 A.1 sequence-tracking state.
     *
     * @param extendedMax extended highest sequence number received
     * @param cycles      number of 16-bit wraps seen so far
     * @param baseSeq     first sequence number seen on this SSRC
     */
    public void updateSequence(long extendedMax, int cycles, long expected, long receivedPrior) {
        this.extendedMax = extendedMax;
        this.cycles = cycles;
        this.baseSeq = expected;
    }

    /** Records the first sequence number seen, i.e. A.1 {@code base_seq}. */
    public void initBaseSequence(long baseSeq) {
        if (this.baseSeq < 0) {
            this.baseSeq = baseSeq;
            this.extendedMax = baseSeq;
        }
    }

    /** Resets the A.1 baseline when the source passes the sequential probation. */
    public void resetBaseSequence(long baseSeq) {
        this.baseSeq = baseSeq;
        this.extendedMax = baseSeq;
    }

    public long receivedPackets() {
        return received.get();
    }

    public long playedPackets() {
        return played.get();
    }

    public long duplicatePackets() {
        return duplicates.get();
    }

    public long lateDroppedPackets() {
        return lateDropped.get();
    }

    public long overloadDroppedPackets() {
        return overloadDropped.get();
    }

    public long rejectedPackets() {
        long total = 0;
        for (AtomicLong counter : rejected.values()) {
            total += counter.get();
        }
        return total;
    }

    public Map<RtpRejectReason, Long> rejectionBreakdown() {
        EnumMap<RtpRejectReason, Long> snapshot = new EnumMap<>(RtpRejectReason.class);
        rejected.forEach((reason, counter) -> snapshot.put(reason, counter.get()));
        return snapshot;
    }

    /** RFC 3550 A.3 cumulative number of packets lost (24-bit on the wire). */
    public long cumulativeLost() {
        if (baseSeq < 0) {
            return 0;
        }
        long uniqueReceived = received.get() - duplicates.get();
        long lost = extendedMax - baseSeq + 1 - uniqueReceived;
        return Math.max(0, lost);
    }

    public long extendedHighestSequence() {
        return extendedMax;
    }

    public long expectedSince(ReceiveCounters other) {
        long expectedNow = baseSeq < 0 ? 0 : extendedMax - baseSeq + 1;
        long expectedThen = other.baseSeq < 0 ? 0 : other.extendedMax - other.baseSeq + 1;
        return Math.max(0, expectedNow - expectedThen);
    }

    public long receivedSince(ReceiveCounters other) {
        long uniqueNow = received.get() - duplicates.get();
        long uniqueThen = other.received.get() - other.duplicates.get();
        return uniqueNow - uniqueThen;
    }

    public double fractionLostSince(ReceiveCounters other) {
        long expected = expectedSince(other);
        if (expected <= 0) {
            return 0;
        }
        long lost = expected - receivedSince(other);
        if (lost <= 0) {
            return 0;
        }
        return Math.min(1.0, (double) lost / expected);
    }

    public void copyInto(ReceiveCounters target) {
        target.received.set(received.get());
        target.played.set(played.get());
        target.duplicates.set(duplicates.get());
        target.lateDropped.set(lateDropped.get());
        target.overloadDropped.set(overloadDropped.get());
        rejected.forEach((reason, counter) -> target.rejected.get(reason).set(counter.get()));
        target.extendedMax = extendedMax;
        target.cycles = cycles;
        target.baseSeq = baseSeq;
    }

    public ReceiveCounters snapshot() {
        ReceiveCounters copy = new ReceiveCounters();
        copyInto(copy);
        return copy;
    }
}
