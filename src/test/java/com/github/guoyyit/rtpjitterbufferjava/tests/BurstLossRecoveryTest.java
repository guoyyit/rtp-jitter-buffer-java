package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.engine.RtpReceiver;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPacket;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPayloadTypes;
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
 * Burst-loss recovery: a forward sequence jump larger than the reorder window
 * must not stall the stream, and statistics must account for the skipped range.
 */
class BurstLossRecoveryTest {

    private static final long TICK_NANOS = 20_000_000L; // 20 ms per packet
    private static final long RTP_TICKS = 160;          // 20 ms at 8 kHz

    private static RtpSessionConfig config(int reorderWindow) {
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(Duration.ofMillis(0))
                .initialDelay(Duration.ofMillis(10))
                .maxDelay(Duration.ofMillis(200))
                .reorderWindowPackets(reorderWindow)
                .build();
        return RtpSessionConfig.builder()
                .localSsrc(Integer.MAX_VALUE)
                .payloadTypes(RtpPayloadTypes.all())
                .jitterBuffer(jitter)
                .build();
    }

    private static void send(RtpSession session, int ssrc, int seq) {
        send(session, ssrc, seq, seq);
    }

    /**
     * Sends one packet; {@code timeline} is the monotonic sender-side packet
     * index driving the RTP timestamp and arrival time. The RTP timestamp is a
     * 32-bit value that keeps increasing when the 16-bit sequence wraps.
     */
    private static void send(RtpSession session, int ssrc, int seq, long timeline) {
        byte[] raw = rtpPacket(0, seq & 0xFFFF, timeline * RTP_TICKS, ssrc,
                false, new byte[]{(byte) seq});
        session.receive(raw, raw.length, timeline * TICK_NANOS);
    }

    private static List<Integer> playedSequences(RtpSession session, long nowNanos) {
        List<Integer> sequences = new ArrayList<>();
        for (RtpPacket packet : session.pollReady(nowNanos)) {
            sequences.add(packet.header().sequenceNumber());
        }
        return sequences;
    }

    private static void assertStrictlyIncreasing(List<Integer> sequences) {
        for (int i = 1; i < sequences.size(); i++) {
            assertTrue(sequences.get(i) > sequences.get(i - 1),
                    "playout must be strictly increasing: " + sequences);
        }
    }

    @Test
    void forwardJumpBeyondWindowRecoversAndPlays() {
        RtpSession session = new RtpSession("burst", config(64));
        for (int seq = 0; seq < 10; seq++) {
            send(session, 111, seq);
        }
        // 99 consecutive packets (10..108) are lost in a burst.
        for (int seq = 109; seq < 120; seq++) {
            send(session, 111, seq);
        }

        List<Integer> played = playedSequences(session, 600_000_000_000L);
        List<Integer> expected = new ArrayList<>();
        for (int seq = 0; seq < 10; seq++) {
            expected.add(seq);
        }
        for (int seq = 109; seq < 120; seq++) {
            expected.add(seq);
        }
        assertEquals(expected, played, "recovered stream must play in sequence");

        ReceiveCounters counters = session.ssrcState(111).orElseThrow().counters();
        assertEquals(21, counters.receivedPackets());
        assertEquals(21, counters.playedPackets());
        assertEquals(99, counters.cumulativeLost(),
                "cumulative loss must cover the whole skipped range");
        session.close();
    }

    @Test
    void noDuplicatesOrRegressionAcrossRecoveryPolls() {
        RtpSession session = new RtpSession("burst", config(64));
        for (int seq = 0; seq < 5; seq++) {
            send(session, 111, seq);
        }
        // Burst loss of 5..104, then the stream resumes.
        for (int seq = 105; seq < 110; seq++) {
            send(session, 111, seq);
        }
        // A retransmitted still-buffered packet collapses as a duplicate.
        send(session, 111, 107);
        List<Integer> first = playedSequences(session, 600_000_000_000L);
        assertEquals(List.of(0, 1, 2, 3, 4, 105, 106, 107, 108, 109), first);

        for (int seq = 110; seq < 115; seq++) {
            send(session, 111, seq);
        }
        List<Integer> second = playedSequences(session, 600_000_000_000L);
        assertEquals(List.of(110, 111, 112, 113, 114), second);

        ReceiveCounters counters = session.ssrcState(111).orElseThrow().counters();
        assertEquals(1, counters.duplicatePackets());
        assertEquals(15, counters.playedPackets());
        session.close();
    }

    @Test
    void lateDropsAfterRecoveryAreCounted() {
        RtpSession session = new RtpSession("burst", config(64));
        for (int seq = 0; seq < 5; seq++) {
            send(session, 111, seq);
        }
        playedSequences(session, 600_000_000_000L); // play 0..4
        // Burst loss of 5..104; stream resumes at 105.
        for (int seq = 105; seq < 110; seq++) {
            send(session, 111, seq);
        }
        // 109 - 30 = 79 > 64: too far behind, dropped and counted as late.
        send(session, 111, 30);
        // 109 - 60 = 49 <= 64: inside the reorder window, still plays.
        send(session, 111, 60);

        List<Integer> played = playedSequences(session, 600_000_000_000L);
        assertEquals(List.of(60, 105, 106, 107, 108, 109), played);
        assertStrictlyIncreasing(played);

        ReceiveCounters counters = session.ssrcState(111).orElseThrow().counters();
        assertEquals(1, counters.lateDroppedPackets());
        assertEquals(0, counters.duplicatePackets());
        session.close();
    }

    @Test
    void cumulativeLossCoversSkippedRangeWithoutDoubleCounting() {
        RtpSession session = new RtpSession("burst", config(64));
        for (int seq = 0; seq < 10; seq++) {
            send(session, 111, seq);
        }
        for (int seq = 109; seq < 112; seq++) { // 10..108 lost
            send(session, 111, seq);
        }
        ReceiveCounters counters = session.ssrcState(111).orElseThrow().counters();
        assertEquals(99, counters.cumulativeLost());

        // Further in-sequence arrivals must not change or double-count the loss.
        ReceiveCounters baseline = counters.snapshot();
        send(session, 111, 112);
        send(session, 111, 113);
        assertEquals(99, counters.cumulativeLost());
        assertEquals(2, counters.expectedSince(baseline));
        assertEquals(2, counters.receivedSince(baseline));
        assertEquals(0.0, counters.fractionLostSince(baseline), 0.0001);
        session.close();
    }

    @Test
    void burstLossAcrossSequenceWrapRecovers() {
        RtpSession session = new RtpSession("burst", config(64));
        for (int seq = 0xFFF0; seq <= 0xFFF9; seq++) {
            send(session, 111, seq, seq - 0xFFF0L);
        }
        // 100 packets lost across the 16-bit wrap: 0xFFFA..0xFFFF and 0..93.
        for (int seq = 94; seq < 105; seq++) {
            send(session, 111, seq, 0x10000L + seq - 0xFFF0L);
        }

        List<Integer> played = playedSequences(session, 600_000_000_000L);
        assertEquals(21, played.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(0xFFF0 + i, played.get(i));
        }
        for (int i = 0; i < 11; i++) {
            assertEquals(94 + i, played.get(10 + i));
        }

        ReceiveCounters counters = session.ssrcState(111).orElseThrow().counters();
        assertEquals(0x10000L + 104, counters.extendedHighestSequence());
        assertEquals(100, counters.cumulativeLost());
        assertEquals(0, counters.duplicatePackets());
        session.close();
    }

    @Test
    void recoveryLeavesOtherSsrcsAndSessionsUntouched() {
        RtpReceiver receiver = new RtpReceiver(10_000);
        RtpSession a = receiver.createSession("a", config(64));
        RtpSession b = receiver.createSession("b", config(64));

        send(a, 111, 0);
        send(a, 111, 1);
        send(a, 222, 0);
        send(b, 333, 0);
        // SSRC 111 in session A loses 500 packets in a burst.
        for (int seq = 502; seq < 510; seq++) {
            send(a, 111, seq);
        }
        send(a, 222, 1);
        send(b, 333, 1);

        assertEquals(500, a.ssrcState(111).orElseThrow().counters().cumulativeLost());
        ReceiveCounters clean = a.ssrcState(222).orElseThrow().counters();
        assertEquals(2, clean.receivedPackets());
        assertEquals(0, clean.cumulativeLost());
        ReceiveCounters otherSession = b.ssrcState(333).orElseThrow().counters();
        assertEquals(2, otherSession.receivedPackets());
        assertEquals(0, otherSession.cumulativeLost());

        List<Integer> playedA = playedSequences(a, 600_000_000_000L);
        assertTrue(playedA.containsAll(List.of(0, 1, 502, 509)));
        assertEquals(List.of(0, 1), playedSequences(b, 600_000_000_000L));
        receiver.close();
    }

    @Test
    void bufferCapsStillApplyDuringRecovery() {
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(Duration.ofSeconds(10))
                .initialDelay(Duration.ofSeconds(10))
                .maxDelay(Duration.ofSeconds(20))
                .reorderWindowPackets(64)
                .maxPacketsPerSsrc(8)
                .build();
        RtpSessionConfig config = RtpSessionConfig.builder()
                .localSsrc(Integer.MAX_VALUE)
                .payloadTypes(RtpPayloadTypes.all())
                .jitterBuffer(jitter)
                .build();
        RtpSession session = new RtpSession("capped", config);
        send(session, 111, 0);
        // Burst loss of 1..99, then a flood that nothing consumes.
        for (int seq = 100; seq < 130; seq++) {
            send(session, 111, seq);
        }
        assertTrue(session.totalBufferedPackets() <= 8,
                "per-SSRC cap must hold during recovery flood");
        assertTrue(session.aggregateOverloadDroppedPackets() > 0);
        session.close();
    }

    @Test
    void delayAndJitterStayBoundedAfterRecovery() {
        RtpSession session = new RtpSession("burst", config(64));
        for (int seq = 0; seq < 10; seq++) {
            send(session, 111, seq);
        }
        for (int seq = 509; seq < 540; seq++) { // 500-packet burst loss
            send(session, 111, seq);
        }
        var buffer = session.ssrcState(111).orElseThrow().jitterBuffer();
        long delay = buffer.currentDelayNanos();
        assertTrue(delay >= 0 && delay <= Duration.ofMillis(200).toNanos(),
                "delay must stay within configured bounds: " + delay);
        assertTrue(buffer.jitterNanos() >= 0);
        session.close();
    }
}
