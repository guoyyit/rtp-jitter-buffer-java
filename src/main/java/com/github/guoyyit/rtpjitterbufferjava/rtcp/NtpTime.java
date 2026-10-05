package com.github.guoyyit.rtpjitterbufferjava.rtcp;

/**
 * 64-bit NTP timestamp helpers (RFC 3550 section 4).
 */
public final class NtpTime {

    /** Seconds between the NTP epoch (1900-01-01) and the Unix epoch (1970-01-01). */
    public static final long EPOCH_OFFSET_SECONDS = 2208988800L;

    public static long now() {
        return fromJavaMillis(System.currentTimeMillis());
    }

    /** Converts Unix epoch milliseconds to a 64-bit NTP timestamp. */
    public static long fromJavaMillis(long epochMillis) {
        long seconds = epochMillis / 1000L + EPOCH_OFFSET_SECONDS;
        long fractionMillis = Math.floorMod(epochMillis, 1000L);
        long fraction = Math.round(fractionMillis / 1000.0 * 0x100000000L);
        return (seconds << 32) | (fraction & 0xFFFFFFFFL);
    }

    /** Converts a 64-bit NTP timestamp back to Unix epoch milliseconds. */
    public static long toJavaMillis(long ntp) {
        long seconds = (ntp >>> 32) - EPOCH_OFFSET_SECONDS;
        long fraction = ntp & 0xFFFFFFFFL;
        return seconds * 1000L + Math.round(fraction / (double) 0x100000000L * 1000.0);
    }

    /** Middle 32 bits (low 16 bits of seconds + high 16 bits of fraction). */
    public static long middle32(long ntp) {
        return (ntp >>> 16) & 0xFFFFFFFFL;
    }

    /** Returns the NTP timestamp {@code nanos} later than {@code ntp}. */
    public static long addNanos(long ntp, long nanos) {
        long seconds = (ntp >>> 32) + Math.floorDiv(nanos, 1_000_000_000L);
        long remainNanos = Math.floorMod(nanos, 1_000_000_000L);
        long fraction = (ntp & 0xFFFFFFFFL)
                + Math.round(remainNanos / 1_000_000_000.0 * 0x100000000L);
        if (fraction >= 0x100000000L) {
            seconds++;
            fraction -= 0x100000000L;
        }
        return (seconds << 32) | (fraction & 0xFFFFFFFFL);
    }

    /** Difference in nanoseconds between two 64-bit NTP timestamps. */
    public static long diffNanos(long newerNtp, long olderNtp) {
        long newerSec = newerNtp >>> 32;
        long olderSec = olderNtp >>> 32;
        double newerFrac = (newerNtp & 0xFFFFFFFFL) / (double) 0x100000000L;
        double olderFrac = (olderNtp & 0xFFFFFFFFL) / (double) 0x100000000L;
        double seconds = (newerSec - olderSec) + (newerFrac - olderFrac);
        return (long) (seconds * 1_000_000_000L);
    }

    /**
     * Round-trip time in nanos from an RR block and the NTP time at which that
     * RR was received (RFC 3550 section 6.4.1): {@code RTT = A - LSR - DLSR}.
     * All arithmetic stays in the 32-bit middle-timestamp domain so it does
     * not depend on the high bits dropped by the on-wire LSR field.
     */
    public static long roundTripTimeNanos(long arrivalNtp, long lsrMiddle32, long dlsrUnits) {
        if (lsrMiddle32 == 0) {
            return 0;
        }
        long arrivalMiddle = middle32(arrivalNtp);
        long deltaUnits = (arrivalMiddle - lsrMiddle32) & 0xFFFFFFFFL;
        if (deltaUnits > 0x7FFFFFFFL) {
            deltaUnits -= 0x100000000L;
        }
        double seconds = (deltaUnits - dlsrUnits) / 65536.0;
        return Math.max(0, Math.round(seconds * 1_000_000_000L));
    }

    private NtpTime() {
    }
}
