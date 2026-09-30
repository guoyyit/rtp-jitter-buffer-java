package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.rtp.SeqNum;
import com.github.guoyyit.rtpjitterbufferjava.rtp.TimeStamp32;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WrapArithmeticTest {

    @Test
    void sequenceDiffHandlesWraparound() {
        assertEquals(1, SeqNum.diff(0, 0xFFFF));
        assertEquals(-1, SeqNum.diff(0xFFFF, 0));
        assertEquals(3, SeqNum.diff(2, 0xFFFF));
        assertEquals(-3, SeqNum.diff(0xFFFE, 1));
    }

    @Test
    void sequenceUnwrapSelectsNearestInstance() {
        assertEquals(0x10000L, SeqNum.unwrap(0xFFFF, 0));
        assertEquals(0xFFFFL, SeqNum.unwrap(0x10000, 0xFFFF));
        assertEquals(0x10005L, SeqNum.unwrap(0xFFFF, 5));
        assertEquals(0L, SeqNum.unwrap(1, 0));
    }

    @Test
    void timestampDiffHandlesWraparound() {
        assertEquals(1L, TimeStamp32.diff(0L, 0xFFFFFFFFL));
        assertEquals(-1L, TimeStamp32.diff(0xFFFFFFFFL, 0L));
        assertEquals(1000L, TimeStamp32.diff(999L, 0xFFFFFFFFL));
    }

    @Test
    void timestampUnwrapSelectsNearestInstance() {
        assertEquals(0x100000000L, TimeStamp32.unwrap(0xFFFFFFFFL, 0L));
        assertEquals(0xFFFFFFFFL, TimeStamp32.unwrap(0x100000000L, 0xFFFFFFFFL));
    }
}
