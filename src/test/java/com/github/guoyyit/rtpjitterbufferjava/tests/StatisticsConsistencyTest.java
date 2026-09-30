package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.NtpTime;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPayloadTypes;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSessionConfig;
import com.github.guoyyit.rtpjitterbufferjava.stats.ReceiveCounters;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.guoyyit.rtpjitterbufferjava.tests.RtpPacketParserTest.rtpPacket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticsConsistencyTest {

    private static RtpSession session() {
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(Duration.ofMillis(0))
                .initialDelay(Duration.ofMillis(20))
                .maxDelay(Duration.ofMillis(200))
                .build();
        RtpSessionConfig config = RtpSessionConfig.builder()
                .localSsrc(Integer.MAX_VALUE)
                .payloadTypes(RtpPayloadTypes.all())
                .jitterBuffer(jitter)
                .build();
        return new RtpSession("stats", config);
    }

    private static void send(RtpSession session, int seq, long rtpTs, long arrivalNanos) {
        byte[] raw = rtpPacket(0, seq, rtpTs, 111, false, new byte[4]);
        session.receive(raw, raw.length, arrivalNanos);
    }

    @Test
    void cumulativeAndIntervalLossMatchActualArrivals() {
        RtpSession session = session();
        // seq 0..9, with seq 3 and 7 lost, seq 5 sent twice (duplicate)
        for (int seq = 0; seq < 10; seq++) {
            if (seq == 3 || seq == 7) {
                continue;
            }
            send(session, seq, seq * 80L, seq * 20_000_000L);
            if (seq == 5) {
                send(session, 5, 5 * 80L, 5 * 20_000_000L + 500_000);
            }
        }

        ReceiveCounters counters = session.ssrcState(111).orElseThrow().counters();
        // 8 unique packets + 1 duplicate = 9 receives; range 0..9 = 10, 2 lost
        assertEquals(9, counters.receivedPackets());
        assertEquals(1, counters.duplicatePackets());
        assertEquals(2, counters.cumulativeLost());

        // Interval math: from a baseline after seq 0 there should be no phantom loss
        ReceiveCounters baseline = counters.snapshot();
        send(session, 10, 10 * 80L, 10 * 20_000_000L);
        long expectedSince = counters.expectedSince(baseline);
        long receivedSince = counters.receivedSince(baseline);
        assertEquals(1, expectedSince);
        assertEquals(1, receivedSince);
        assertEquals(0.0, counters.fractionLostSince(baseline), 0.0001);
    }

    @Test
    void wrapAroundKeepsLossAndSequenceState() {
        RtpSession session = session();
        send(session, 0xFFFE, 0, 0);
        send(session, 0xFFFF, 80, 20_000_000);
        send(session, 0, 160, 40_000_000);
        send(session, 2, 320, 80_000_000); // seq 1 (0x10000) lost

        ReceiveCounters counters = session.ssrcState(111).orElseThrow().counters();
        assertEquals(0x10002L, counters.extendedHighestSequence());
        assertEquals(1, counters.cumulativeLost());
        assertEquals(0, counters.duplicatePackets());
    }

    @Test
    void jitterIsPositiveUnderVariableArrivalAndBounded() {
        RtpSession session = session();
        long[] arrivals = {0, 18_000_000, 42_000_000, 58_000_000, 85_000_000};
        for (int i = 0; i < arrivals.length; i++) {
            send(session, i, i * 80L, arrivals[i]);
        }
        session.pollReady(1_000_000_000);
        long jitter = session.ssrcState(111).orElseThrow().jitterBuffer().jitterNanos();
        assertTrue(jitter > 0, "jitter should reflect variable spacing");
        assertTrue(session.ssrcState(111).orElseThrow().jitterBuffer().currentDelayNanos()
                <= Duration.ofMillis(200).toNanos());
    }

    @Test
    void playedCountMatchesWhatPollReadyReturned() {
        RtpSession session = session();
        for (int seq = 0; seq < 6; seq++) {
            send(session, seq, seq * 80L, seq * 20_000_000L);
        }
        int played = session.pollReady(1_000_000_000).size();
        assertEquals(6, played);
        assertEquals(6, session.aggregatePlayedPackets());
    }

    @Test
    void ntpRoundTripPreservesMilliseconds() {
        long millis = System.currentTimeMillis();
        long ntp = NtpTime.fromJavaMillis(millis);
        assertEquals(millis, NtpTime.toJavaMillis(ntp));
    }

    @Test
    void roundTripTimeFollowsLsrAndDlsr() {
        long srNtp = NtpTime.fromJavaMillis(100_000);
        long lsr = NtpTime.middle32(srNtp);
        // RR arrives 120 ms after the SR; receiver waited 80 ms before replying.
        long rrArrival = NtpTime.fromJavaMillis(100_120);
        long dlsrUnits = Math.round(0.080 * 65536.0);
        long rttNanos = NtpTime.roundTripTimeNanos(rrArrival, lsr, dlsrUnits);
        assertEquals(40_000_000L, rttNanos, 1_000_000L);
        assertEquals(0L, NtpTime.roundTripTimeNanos(rrArrival, 0, dlsrUnits));
    }
}
