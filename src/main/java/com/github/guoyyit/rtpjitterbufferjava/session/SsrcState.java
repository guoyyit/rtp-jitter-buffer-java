package com.github.guoyyit.rtpjitterbufferjava.session;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBuffer;
import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPacket;
import com.github.guoyyit.rtpjitterbufferjava.rtp.SeqNum;
import com.github.guoyyit.rtpjitterbufferjava.stats.ReceiveCounters;

/**
 * Per-SSRC receive state: sequence/timestamp tracking, jitter buffer and stats.
 */
public class SsrcState {

    private final long ssrc;
    private final JitterBuffer jitterBuffer;
    private final ReceiveCounters counters = new ReceiveCounters();
    private final long clockRate;

    private boolean initialized;
    private long baseSeq;
    private long maxSeq;
    private int cycles;
    private long lastArrivalNanos;

    public SsrcState(long ssrc) {
        this(ssrc, JitterBufferConfig.defaults(), 8000);
    }

    public SsrcState(long ssrc, JitterBufferConfig config, long clockRate) {
        this.ssrc = ssrc;
        this.clockRate = clockRate;
        this.jitterBuffer = new JitterBuffer(ssrc, config, clockRate);
    }

    public long ssrc() {
        return ssrc;
    }

    public JitterBuffer jitterBuffer() {
        return jitterBuffer;
    }

    public ReceiveCounters counters() {
        return counters;
    }

    /**
     * Feeds a packet into the RFC 3550 A.1 sequence tracker and stats.
     * Duplicate/late admission is decided afterwards by the jitter buffer,
     * which alone knows what has already been played or buffered.
     */
    public void onPacket(RtpPacket packet) {
        lastArrivalNanos = packet.arrivalNanos();
        counters.recordReceived();
        int seq = packet.header().sequenceNumber();

        if (!initialized) {
            initialized = true;
            baseSeq = seq;
            maxSeq = seq;
            counters.initBaseSequence(baseSeq);
            counters.updateSequence(maxSeq, cycles, baseSeq, 0);
            return;
        }

        long udelta = SeqNum.diff(seq, (int) maxSeq);
        if (udelta > 0 && seq < (maxSeq & 0xFFFF)) {
            cycles++;
        }
        if (udelta > 0) {
            maxSeq = (cycles << 16) | (seq & 0xFFFFL);
            counters.updateSequence(maxSeq, cycles, baseSeq, 0);
        }
    }

    /** Current extended highest sequence number for this SSRC. */
    public long extendedMaxSequence() {
        return maxSeq;
    }

    public long lastArrivalNanos() {
        return lastArrivalNanos;
    }

    /**
     * RFC 3550 A.8 interarrival jitter expressed in RTP clock units for use in
     * the RTCP report block.
     */
    public long interarrivalJitterClockUnits() {
        long jitterNanos = jitterBuffer.jitterNanos();
        return jitterNanos * clockRate / 1_000_000_000L;
    }
}
