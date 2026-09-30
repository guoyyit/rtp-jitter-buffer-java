package com.github.guoyyit.rtpjitterbufferjava.rtp;

/**
 * 16-bit RTP sequence number arithmetic with wrap handling.
 */
public final class SeqNum {

    /**
     * Signed forward distance from {@code older} to {@code newer} in 16-bit space.
     * Positive means {@code newer} is ahead of {@code older}; the result stays
     * inside [-32768, 32767] so wrap-around at 0xFFFF/0x0000 is handled.
     */
    public static int diff(int newer, int older) {
        return (newer - older) << 16 >> 16;
    }

    /**
     * Advances a 16-bit sequence number by a (possibly negative) delta.
     */
    public static int advance(int seq, int delta) {
        return (seq + delta) & 0xFFFF;
    }

    /**
     * Extends a 16-bit sequence number to 64 bits using the high bits derived
     * from an already-unwrapped neighbour, following the RFC 3550 A.1 idea of
     * choosing the candidate closest to the expected value.
     */
    public static long unwrap(long expectedHigh, int seq) {
        long candidate = (expectedHigh & ~0xFFFFL) | (seq & 0xFFFFL);
        if (candidate - expectedHigh > 0x7FFFL) {
            candidate -= 0x10000L;
        } else if (expectedHigh - candidate > 0x7FFFL) {
            candidate += 0x10000L;
        }
        return candidate;
    }

    private SeqNum() {
    }
}
