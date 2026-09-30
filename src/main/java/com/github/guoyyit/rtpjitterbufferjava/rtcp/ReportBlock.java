package com.github.guoyyit.rtpjitterbufferjava.rtcp;

/**
 * One RTCP reception report block (24 bytes, RFC 3550 section 6.4.1).
 */
public class ReportBlock {

    private final long ssrc;
    private final int fractionLost;
    private final long cumulativePacketsLost;
    private final long extendedHighestSequence;
    private final long interarrivalJitter;
    private final long lastSr;
    private final long delaySinceLastSr;

    public ReportBlock(long ssrc, int fractionLost, long cumulativePacketsLost,
                       long extendedHighestSequence, long interarrivalJitter,
                       long lastSr, long delaySinceLastSr) {
        this.ssrc = ssrc & 0xFFFFFFFFL;
        this.fractionLost = fractionLost & 0xFF;
        this.cumulativePacketsLost = cumulativePacketsLost & 0xFFFFFFL;
        this.extendedHighestSequence = extendedHighestSequence & 0xFFFFFFFFL;
        this.interarrivalJitter = interarrivalJitter & 0xFFFFFFFFL;
        this.lastSr = lastSr & 0xFFFFFFFFL;
        this.delaySinceLastSr = delaySinceLastSr & 0xFFFFFFFFL;
    }

    public long ssrc() {
        return ssrc;
    }

    public int fractionLost() {
        return fractionLost;
    }

    public long cumulativePacketsLost() {
        return cumulativePacketsLost;
    }

    public long extendedHighestSequence() {
        return extendedHighestSequence;
    }

    public long interarrivalJitter() {
        return interarrivalJitter;
    }

    public long lastSr() {
        return lastSr;
    }

    public long delaySinceLastSr() {
        return delaySinceLastSr;
    }

    void writeTo(byte[] buffer, int offset) {
        writeUInt32(buffer, offset, ssrc);
        buffer[offset + 4] = (byte) fractionLost;
        buffer[offset + 5] = (byte) (cumulativePacketsLost >> 16);
        buffer[offset + 6] = (byte) (cumulativePacketsLost >> 8);
        buffer[offset + 7] = (byte) cumulativePacketsLost;
        writeUInt32(buffer, offset + 8, extendedHighestSequence);
        writeUInt32(buffer, offset + 12, interarrivalJitter);
        writeUInt32(buffer, offset + 16, lastSr);
        writeUInt32(buffer, offset + 20, delaySinceLastSr);
    }

    static ReportBlock readFrom(byte[] buffer, int offset) {
        long ssrc = readUInt32(buffer, offset);
        int fractionLost = buffer[offset + 4] & 0xFF;
        long cumulative = ((buffer[offset + 5] & 0xFFL) << 16)
                | ((buffer[offset + 6] & 0xFFL) << 8)
                | (buffer[offset + 7] & 0xFFL);
        long extended = readUInt32(buffer, offset + 8);
        long jitter = readUInt32(buffer, offset + 12);
        long lastSr = readUInt32(buffer, offset + 16);
        long delay = readUInt32(buffer, offset + 20);
        return new ReportBlock(ssrc, fractionLost, cumulative, extended,
                jitter, lastSr, delay);
    }

    static void writeUInt32(byte[] buffer, int offset, long value) {
        buffer[offset] = (byte) (value >> 24);
        buffer[offset + 1] = (byte) (value >> 16);
        buffer[offset + 2] = (byte) (value >> 8);
        buffer[offset + 3] = (byte) value;
    }

    static long readUInt32(byte[] buffer, int offset) {
        return ((buffer[offset] & 0xFFL) << 24)
                | ((buffer[offset + 1] & 0xFFL) << 16)
                | ((buffer[offset + 2] & 0xFFL) << 8)
                | (buffer[offset + 3] & 0xFFL);
    }
}
