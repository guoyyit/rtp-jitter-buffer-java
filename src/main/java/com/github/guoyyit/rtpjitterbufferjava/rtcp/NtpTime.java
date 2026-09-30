package com.github.guoyyit.rtpjitterbufferjava.rtcp;

/**
 * Conversion between Java millisecond timestamps and 64-bit NTP timestamps.
 * NTP values are returned as Java {@code long}; bit patterns above
 * {@code Long.MAX_VALUE} are unsigned and must be encoded with
 * {@code ByteBuffer.putLong(Long.toHexString-free raw bits)} style methods.
 */
public final class NtpTime {

    private static final long NTP_EPOCH_OFFSET_MILLIS = 2_208_988_800_000L;
    private static final long FRACTION_SCALE = 0x100000000L;

    private NtpTime() {
    }

    public static long fromMillis(long epochMillis) {
        long seconds = Math.floorDiv(epochMillis, 1000L) + NTP_EPOCH_OFFSET_MILLIS / 1000L;
        long millisRemainder = Math.floorMod(epochMillis, 1000L);
        long fraction = millisRemainder * FRACTION_SCALE / 1000L;
        return (seconds << 32) | fraction;
    }

    public static long middle32(long ntpTimestamp) {
        return (ntpTimestamp >>> 16) & 0xFFFFFFFFL;
    }
}
