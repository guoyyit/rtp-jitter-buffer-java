package com.github.guoyyit.rtpjitterbufferjava.rtp;

/**
 * A parsed RTP packet together with its arrival time in transport nanoseconds.
 */
public class RtpPacket {

    private final RtpHeader header;
    private final byte[] raw;
    private final int payloadOffset;
    private final int payloadLength;
    private final int paddingBytes;
    private final long arrivalNanos;

    public RtpPacket(RtpHeader header, byte[] raw, int payloadOffset, int payloadLength,
                     int paddingBytes, long arrivalNanos) {
        this.header = header;
        this.raw = raw;
        this.payloadOffset = payloadOffset;
        this.payloadLength = payloadLength;
        this.paddingBytes = paddingBytes;
        this.arrivalNanos = arrivalNanos;
    }

    public RtpHeader header() {
        return header;
    }

    /** Defensive copy of the payload bytes excluding padding. */
    public byte[] payload() {
        byte[] out = new byte[payloadLength];
        System.arraycopy(raw, payloadOffset, out, 0, payloadLength);
        return out;
    }

    public int payloadLength() {
        return payloadLength;
    }

    public int paddingBytes() {
        return paddingBytes;
    }

    public long arrivalNanos() {
        return arrivalNanos;
    }
}
