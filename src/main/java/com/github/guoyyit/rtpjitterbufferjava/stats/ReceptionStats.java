package com.github.guoyyit.rtpjitterbufferjava.stats;

import com.github.guoyyit.rtpjitterbufferjava.buffer.EnqueueResult;
import com.github.guoyyit.rtpjitterbufferjava.clock.Clock;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.NtpTime;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.ReportBlock;

import java.util.TreeSet;

public class ReceptionStats {

    private static final int MAX_DROPOUT = 3000;
    private static final int MAX_MISORDER = 100;

    private final long ssrc;
    private final int clockRate;

    private long baseSeq;
    private long receivedPackets;
    private long receivedPrior;
    private long expectedPrior;
    private long jitter;
    private boolean lastTransitSet;
    private long lastArrivalRtpTime;
    private long lastRtpTimestamp;
    private boolean initialized;
    private long extendedHighest;
    private long duplicatePackets;
    private long reorderedPackets;

    private long lastSrNtp;
    private long lastSrArrivalNtp;
    private long roundTripNtpUnits;
    private long bufferDiscards;
    private final TreeSet<Long> seenSequences = new TreeSet<>();

    public ReceptionStats(long ssrc, int clockRate, Clock clock) {
        this.ssrc = ssrc;
        this.clockRate = clockRate;
        this.baseSeq = -1;
    }

    public SeqUpdate updateSequence(int sequence) {
        receivedPackets++;
        long seq = sequence & 0xFFFFL;

        if (!initialized) {
            baseSeq = seq;
            receivedPrior = 0;
            expectedPrior = 0;
            receivedPackets = 1;
            initialized = true;
            extendedHighest = seq;
            seenSequences.add(seq);
            return new SeqUpdate(SeqStatus.ACCEPTED, seq);
        }

        long extendedSeq = extendAgainst(seq, extendedHighest);

        if (seenSequences.contains(extendedSeq)) {
            duplicatePackets++;
            receivedPackets--;
            return new SeqUpdate(SeqStatus.DUPLICATE, extendedSeq);
        }

        long delta = extendedSeq - extendedHighest;
        if (delta > 0 && delta <= MAX_DROPOUT) {
            extendedHighest = extendedSeq;
        } else if (delta < 0 && delta >= -MAX_MISORDER) {
            reorderedPackets++;
            seenSequences.add(extendedSeq);
            pruneSeenWindow();
            return new SeqUpdate(SeqStatus.REORDERED, extendedSeq);
        } else if (delta < 0) {
            reorderedPackets++;
            return new SeqUpdate(SeqStatus.LATE, extendedSeq);
        } else {
            receivedPackets--;
            return new SeqUpdate(SeqStatus.INVALID_JUMP, extendedSeq);
        }

        seenSequences.add(extendedSeq);
        pruneSeenWindow();
        return new SeqUpdate(SeqStatus.ACCEPTED, extendedHighest);
    }

    /**
     * Classifies a sequence number without mutating any counters. Used by the
     * session to extend a 16-bit sequence against the current wrap position
     * before the packet is admitted into the jitter buffer.
     */
    public SeqUpdate peekSequence(int sequence) {
        long seq = sequence & 0xFFFFL;
        if (!initialized) {
            return new SeqUpdate(SeqStatus.ACCEPTED, seq);
        }
        long extendedSeq = extendAgainst(seq, extendedHighest);
        long delta = extendedSeq - extendedHighest;
        if (seenSequences.contains(extendedSeq)) {
            return new SeqUpdate(SeqStatus.DUPLICATE, extendedSeq);
        }
        if (delta > MAX_DROPOUT) {
            return new SeqUpdate(SeqStatus.INVALID_JUMP, extendedSeq);
        }
        if (delta < 0) {
            return new SeqUpdate(SeqStatus.REORDERED, extendedSeq);
        }
        return new SeqUpdate(SeqStatus.ACCEPTED, extendedSeq);
    }

    /**
     * Records a packet that was observed but not admitted to the buffer
     * (duplicate or late), keeping per-SSRC diagnostic counters aligned with
     * the session-level counters without affecting RFC 3550 received/loss math.
     */
    public void recordNonAccepted(SeqStatus status) {
        if (status == SeqStatus.DUPLICATE) {
            duplicatePackets++;
        }
    }

    private void pruneSeenWindow() {
        long floor = extendedHighest - MAX_MISORDER;
        while (!seenSequences.isEmpty() && seenSequences.first() < floor) {
            seenSequences.pollFirst();
        }
    }

    public void updateJitter(long rtpTimestamp, long arrivalMillis) {
        long arrivalRtpTime = (long) ((double) arrivalMillis * clockRate / 1000.0);
        long difference;
        if (!lastTransitSet) {
            difference = 0;
            lastTransitSet = true;
        } else {
            long arrivalDelta = arrivalRtpTime - lastArrivalRtpTime;
            long sentDelta = signed32(rtpTimestamp - lastRtpTimestamp);
            difference = Math.abs(arrivalDelta - sentDelta);
        }
        lastArrivalRtpTime = arrivalRtpTime;
        lastRtpTimestamp = rtpTimestamp;
        jitter += (difference - jitter) / 16;
    }

    private static long signed32(long value) {
        value <<= 32;
        return value >> 32;
    }

    /**
     * Remembers the wall-clock instant (in NTP 64-bit format) of a received SR so
     * that subsequent receiver reports can echo LSR and DLSR.
     */
    public void recordReceivedSenderReport(long ntpTimestamp, long arrivalNtpTimestamp) {
        lastSrNtp = ntpTimestamp;
        lastSrArrivalNtp = arrivalNtpTimestamp;
    }

    /**
     * Computes RTT using RFC 3550 appendix A.8:
     * round trip delay = now - DLSR - LSR (all in 1/65536 second NTP units).
     */
    public void recordSentReceiverReport(long nowNtpTimestamp) {
        if (lastSrNtp == 0 || lastSrArrivalNtp == 0) {
            return;
        }
        long dlsr = nowNtpTimestamp - lastSrArrivalNtp;
        if (dlsr < 0) {
            return;
        }
        long rtt = lastSrArrivalNtp - lastSrNtp;
        if (rtt >= 0) {
            roundTripNtpUnits = rtt;
        }
    }

    public ReportBlock buildReportBlock(long nowNtpTimestamp) {
        long extendedMax = extendedHighest;
        long expected = extendedMax - baseSeq + 1;
        long lostTotal = Math.max(0, expected - receivedPackets);

        long expectedInterval = expected - expectedPrior;
        expectedPrior = expected;
        long receivedInterval = receivedPackets - receivedPrior;
        receivedPrior = receivedPackets;

        int fractionLost = 0;
        if (expectedInterval > 0 && expectedInterval > receivedInterval) {
            long lostInterval = expectedInterval - receivedInterval;
            fractionLost = (int) ((lostInterval << 8) / expectedInterval);
        }

        long lsr = NtpTime.middle32(lastSrNtp);
        long dlsr = 0;
        if (lastSrArrivalNtp != 0) {
            dlsr = Math.max(0, nowNtpTimestamp - lastSrArrivalNtp);
        }

        return new ReportBlock(ssrc, fractionLost, (int) Math.min(Integer.MAX_VALUE, lostTotal),
                extendedMax, jitter, lsr, dlsr);
    }

    public long getSsrc() {
        return ssrc;
    }

    public long getExtendedHighestSequence() {
        return extendedHighest;
    }

    public long getReceivedPackets() {
        return receivedPackets;
    }

    public long getCumulativeLost() {
        if (!initialized) {
            return 0;
        }
        return Math.max(0, extendedHighest - baseSeq + 1 - receivedPackets);
    }

    public long getJitter() {
        return jitter;
    }

    public long getDuplicatePackets() {
        return duplicatePackets;
    }

    public long getReorderedPackets() {
        return reorderedPackets;
    }

    public long getRoundTripTimeNtpUnits() {
        return roundTripNtpUnits;
    }

    public void notifyBufferDiscard(EnqueueResult result, boolean reordered) {
        if (result == EnqueueResult.LATE_DROPPED
                || result == EnqueueResult.SESSION_FULL
                || result == EnqueueResult.GLOBAL_FULL) {
            bufferDiscards++;
        }
    }

    public long getBufferDiscards() {
        return bufferDiscards;
    }

    private static long extendAgainst(long seq, long reference) {
        long refCycle = reference & ~0xFFFFL;
        long candidate = refCycle + seq;
        long diff = candidate - reference;
        if (diff > 0x7FFFL) {
            candidate -= 0x10000L;
        } else if (diff < -0x7FFFL) {
            candidate += 0x10000L;
        }
        return candidate;
    }
}
