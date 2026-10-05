package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPayloadTypes;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPacket;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSessionConfig;
import com.github.guoyyit.rtpjitterbufferjava.stats.ReceiveCounters;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.github.guoyyit.rtpjitterbufferjava.tests.RtpPacketParserTest.rtpPacket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forward burst-loss recovery: a run of losses longer than the reorder window
 * must not stop the stream, and post-recovery stats must stay consistent.
 */
class BurstLossRecoveryTest {

    private static final int REMOTE_SSRC = 111;

    private static RtpSession session(int window) {
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(Duration.ofMillis(0))
                .initialDelay(Duration.ofMillis(5))
                .maxDelay(Duration.ofMillis(100))
                .reorderWindowPackets(window)
                .build();
        RtpSessionConfig config = RtpSessionConfig.builder()
                .localSsrc(Integer.MAX_VALUE)
                .payloadTypes(RtpPayloadTypes.all())
                .jitterBuffer(jitter)
                .build();
        return new RtpSession("burst", config);
    }

    private static void send(RtpSession session, int seq, long arrivalNanos) {
        byte[] raw = rtpPacket(0, seq, seq * 80L, REMOTE_SSRC, false, new byte[]{(byte) seq});
        session.receive(raw, raw.length, arrivalNanos);
    }

    @Test
    void resumesAfterBurstLongerThanWindowWithoutDuplicatesOrRewind() {
        RtpSession session = session(64);
        long period = 20_000_000L;

        send(session, 0, 0);
        session.pollReady(period);

        // 99 consecutive losses (seq 1..99), then seq 100 resumes the stream.
        send(session, 100, 100 * period);
        for (int seq = 101; seq <= 110; seq++) {
            send(session, seq, seq * period);
        }

        List<Integer> played = new ArrayList<>();
        session.pollReady(200 * period).forEach(p ->
                played.add(p.header().sequenceNumber()));

        assertTrue(played.contains(100), "resumed packet must play: " + played);
        for (int i = 1; i < played.size(); i++) {
            assertTrue(played.get(i) > played.get(i - 1),
                    "playout must be strictly increasing: " + played);
        }
        long distinct = played.stream().distinct().count();
        assertEquals(played.size(), distinct, "no duplicate output");

        ReceiveCounters counters = session.ssrcState(REMOTE_SSRC).orElseThrow().counters();
        // Range 0..110 is 111 packets; received unique = 1 (seq 0) + 11 (100..110).
        assertEquals(12, counters.receivedPackets() - counters.duplicatePackets());
        assertEquals(99, counters.cumulativeLost());
        assertEquals(110L, counters.extendedHighestSequence());
    }

    @Test
    void skippedRegionPacketsAreLateWhenTheyArriveAfterward() {
        RtpSession session = session(64);
        long period = 20_000_000L;
        send(session, 0, 0);
        session.pollReady(period);
        send(session, 100, 100 * period);

        // Gap packets arriving after the new highest sequence: distance > window
        // must be dropped as late; distance <= window may still be reordered.
        send(session, 10, 101 * period);   // 90 behind highest -> late
        send(session, 20, 102 * period);   // 80 behind -> late
        assertEquals(2, session.aggregateLateDroppedPackets());

        send(session, 50, 103 * period);   // 50 behind, within window -> buffered
        List<RtpPacket> played = session.pollReady(200 * period);
        assertTrue(played.size() >= 2, "seq 90 and 100 must both play");
        assertEquals(50, played.get(0).header().sequenceNumber());
        assertEquals(100, played.get(1).header().sequenceNumber());
        assertEquals(2, session.aggregateLateDroppedPackets());
    }

    @Test
    void duplicatesStillCollapseAfterRecovery() {
        RtpSession session = session(64);
        long period = 20_000_000L;
        send(session, 0, 0);
        send(session, 200, 200 * period);
        send(session, 200, 200 * period + 1_000); // duplicate resume packet
        ReceiveCounters counters = session.ssrcState(REMOTE_SSRC).orElseThrow().counters();
        assertEquals(1, counters.duplicatePackets());
        // 3 receives total, 2 unique, range 0..200 -> 199 lost.
        assertEquals(199, counters.cumulativeLost());
    }

    @Test
    void recoveredPacketsDrainInsteadOfLingering() {
        RtpSession session = session(64);
        long period = 20_000_000L;
        send(session, 0, 0);
        session.pollReady(period);
        // 200-loss burst then resume; stream continues at 20 ms cadence.
        for (int seq = 200; seq < 240; seq++) {
            send(session, seq, seq * period);
        }
        session.pollReady(300 * period);
        assertEquals(0, session.totalBufferedPackets(),
                "no recovered packet should linger past its playout time");
        // Adaptive delay remains within configured bounds after the burst.
        long delayNanos = session.ssrcState(REMOTE_SSRC).orElseThrow()
                .jitterBuffer().currentDelayNanos();
        assertTrue(delayNanos >= Duration.ofMillis(0).toNanos());
        assertTrue(delayNanos <= Duration.ofMillis(100).toNanos());
    }
}
