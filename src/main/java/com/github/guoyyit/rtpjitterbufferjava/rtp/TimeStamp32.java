package com.github.guoyyit.rtpjitterbufferjava.rtp;

/**
 * 32-bit RTP timestamp arithmetic with wrap handling.
 */
public final class TimeStamp32 {

    /**
     * Signed forward distance from {@code older} to {@code newer} in 32-bit
     * RTP timestamp space, correctly handling the 2^32 wrap.
     */
    public static long diff(long newer, long older) {
        long delta = (newer - older) & 0xFFFFFFFFL;
        if (delta > 0x7FFFFFFFL) {
            delta -= 0x100000000L;
        }
        return delta;
    }

    /**
     * Extends a 32-bit timestamp to 64 bits by selecting the wrap instance
     * closest to an already-unwrapped neighbour value.
     */
    public static long unwrap(long expectedHigh, long ts) {
        long candidate = (expectedHigh & ~0xFFFFFFFFL) | (ts & 0xFFFFFFFFL);
        if (candidate - expectedHigh > 0x7FFFFFFFL) {
            candidate -= 0x100000000L;
        } else if (expectedHigh - candidate > 0x7FFFFFFFL) {
            candidate += 0x100000000L;
        }
        return candidate;
    }

    private TimeStamp32() {
    }
}
