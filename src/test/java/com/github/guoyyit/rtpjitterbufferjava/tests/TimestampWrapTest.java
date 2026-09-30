package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.clock.TestClock;
import com.github.guoyyit.rtpjitterbufferjava.stats.ReceptionStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TimestampWrapTest {

    @Test
    void jitterStaysSaneAcross32BitTimestampWrap() {
        TestClock clock = new TestClock();
        ReceptionStats stats = new ReceptionStats(1, 8000, clock);

        long timestamp = 0xFFFFFF60L;
        long arrival = 0;
        for (int seq = 0; seq < 12; seq++) {
            clock.setTime(arrival);
            stats.updateSequence(seq);
            stats.updateJitter(timestamp & 0xFFFFFFFFL, arrival);
            timestamp += 160L;
            arrival += 20L;
        }

        long jitter = stats.getJitter();
        assertTrue(jitter >= 0 && jitter < 8000,
                "jitter must not explode at the 32-bit wrap point, got " + jitter);
    }

    @Test
    void detectsSpikeAcrossTimestampWrap() {
        TestClock clock = new TestClock();
        ReceptionStats stats = new ReceptionStats(1, 8000, clock);

        long timestamp = 0xFFFFFF60L;
        long arrival = 0;
        for (int seq = 0; seq < 10; seq++) {
            long thisArrival = seq * 20L + (seq == 6 ? 100L : 0L);
            clock.setTime(thisArrival);
            stats.updateSequence(seq);
            stats.updateJitter(timestamp & 0xFFFFFFFFL, thisArrival);
            timestamp += 160L;
            arrival = thisArrival;
        }

        assertTrue(stats.getJitter() > 0,
                "a transit spike at the wrap must still register as jitter");
    }
}
