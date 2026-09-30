package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPayloadTypes;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpRejectReason;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSessionConfig;
import com.github.guoyyit.rtpjitterbufferjava.session.SsrcConflictPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.guoyyit.rtpjitterbufferjava.tests.RtpPacketParserTest.rtpPacket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpSessionTest {

    private static RtpSessionConfig configWithLocalSsrc(int localSsrc, SsrcConflictPolicy policy) {
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(Duration.ofMillis(0))
                .initialDelay(Duration.ofMillis(10))
                .maxDelay(Duration.ofMillis(100))
                .build();
        return RtpSessionConfig.builder()
                .localSsrc(localSsrc)
                .clockRate(8000)
                .payloadTypes(RtpPayloadTypes.of(0))
                .jitterBuffer(jitter)
                .conflictPolicy(policy)
                .build();
    }

    private static void send(RtpSession session, int ssrc, int seq, long arrivalNanos) {
        byte[] raw = rtpPacket(0, seq, seq * 80L, ssrc, false, new byte[]{(byte) seq});
        session.receive(raw, raw.length, arrivalNanos);
    }

    @Test
    void multiplexesMultipleSsrcsWithIndependentState() {
        RtpSession session = new RtpSession("s", configWithLocalSsrc(999, SsrcConflictPolicy.SWITCH));
        send(session, 111, 0, 0);
        send(session, 111, 2, 20_000_000);
        send(session, 222, 0, 1_000_000);
        send(session, 222, 1, 21_000_000);

        assertNotNull(session.ssrcState(111).orElse(null));
        assertNotNull(session.ssrcState(222).orElse(null));
        assertEquals(2, session.ssrcState(111).orElseThrow().counters().receivedPackets());
        assertEquals(2, session.ssrcState(222).orElseThrow().counters().receivedPackets());
        assertEquals(1, session.ssrcState(111).orElseThrow().counters().cumulativeLost());
        assertEquals(0, session.ssrcState(222).orElseThrow().counters().cumulativeLost());
    }

    @Test
    void countsDuplicatesAndLostConsistently() {
        RtpSession session = new RtpSession("s", configWithLocalSsrc(999, SsrcConflictPolicy.SWITCH));
        send(session, 111, 0, 0);
        send(session, 111, 1, 10_000_000);
        send(session, 111, 1, 11_000_000); // duplicate
        send(session, 111, 3, 30_000_000); // gap: seq 2 lost

        var counters = session.ssrcState(111).orElseThrow().counters();
        assertEquals(4, counters.receivedPackets());
        assertEquals(1, counters.duplicatePackets());
        assertEquals(1, counters.cumulativeLost());
    }

    @Test
    void rejectsMalformedAndUnknownPayloadTypeWithCounters() {
        RtpSession session = new RtpSession("s", configWithLocalSsrc(999, SsrcConflictPolicy.SWITCH));
        byte[] badVersion = rtpPacket(0, 0, 0, 1, false, new byte[1]);
        badVersion[0] = 0x40;
        session.receive(badVersion, badVersion.length, 0);
        byte[] truncated = rtpPacket(0, 0, 0, 1, false, new byte[1]);
        session.receive(truncated, 4, 0);
        byte[] unknownPt = rtpPacket(96, 0, 0, 1, false, new byte[1]);
        session.receive(unknownPt, unknownPt.length, 0);

        assertEquals(1, session.counters().rejectionBreakdown().get(RtpRejectReason.VERSION_MISMATCH));
        assertEquals(1, session.counters().rejectionBreakdown().get(RtpRejectReason.TRUNCATED_PACKET));
        assertEquals(1, session.counters().rejectionBreakdown().get(RtpRejectReason.UNKNOWN_PAYLOAD_TYPE));
        assertEquals(3, session.counters().rejectedPackets());
    }

    @Test
    void switchesLocalSsrcOnCollisionByDefault() {
        RtpSession session = new RtpSession("s", configWithLocalSsrc(42, SsrcConflictPolicy.SWITCH));
        send(session, 42, 0, 0);
        assertEquals(1, session.conflictEvents().size());
        assertFalse(session.terminated());
        assertEquals(43, session.localSsrc());
        assertTrue(session.conflictEvents().get(0).reason().contains("switching"));
    }

    @Test
    void terminatesOnCollisionWhenConfigured() {
        RtpSession session = new RtpSession("s", configWithLocalSsrc(42, SsrcConflictPolicy.TERMINATE));
        send(session, 42, 0, 0);
        assertTrue(session.terminated());
        assertTrue(session.conflictEvents().get(0).terminated());
        // terminated sessions silently discard further media
        send(session, 7, 0, 1_000_000);
        assertTrue(session.ssrcState(7).isEmpty());
    }

    @Test
    void latePacketsBeyondPlayedPointAreCounted() {
        RtpSession session = new RtpSession("s", configWithLocalSsrc(999, SsrcConflictPolicy.SWITCH));
        send(session, 111, 0, 0);
        send(session, 111, 1, 20_000_000);
        session.pollReady(1_000_000_000);
        send(session, 111, 0, 1_001_000_000); // already played -> late
        assertEquals(1, session.aggregateLateDroppedPackets());
    }
}
