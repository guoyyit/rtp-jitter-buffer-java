package com.github.guoyyit.rtpjitterbufferjava.buffer;

import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPacket;

/**
 * An RTP packet held in the jitter buffer, keyed by its unwrapped sequence.
 */
public class BufferedPacket {

    private final long extendedSequence;
    private final long rtpTimestamp;
    private final RtpPacket packet;

    public BufferedPacket(long extendedSequence, long rtpTimestamp, RtpPacket packet) {
        this.extendedSequence = extendedSequence;
        this.rtpTimestamp = rtpTimestamp;
        this.packet = packet;
    }

    public long extendedSequence() {
        return extendedSequence;
    }

    public long rtpTimestamp() {
        return rtpTimestamp;
    }

    public RtpPacket packet() {
        return packet;
    }
}
