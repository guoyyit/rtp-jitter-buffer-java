package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.NtpTime;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.ReportBlock;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpMessage;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacketBuilder;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacketParser;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpRr;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpSdes;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.SenderInfo;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpRejectReason;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPayloadTypes;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSessionConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.github.guoyyit.rtpjitterbufferjava.tests.RtpPacketParserTest.rtpPacket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RTP/RTCP demultiplexing and RR block fields derived from real receive stats.
 */
class RtcpReportFieldsTest {

    private static final int LOCAL_SSRC = 4242;
    private static final int REMOTE_SSRC = 7777;
    private static final String CNAME = "receiver@rtcp-test";

    private static RtpSessionConfig sessionConfig() {
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(Duration.ofMillis(0))
                .initialDelay(Duration.ofMillis(5))
                .maxDelay(Duration.ofMillis(100))
                .reorderWindowPackets(64)
                .build();
        return RtpSessionConfig.builder()
                .localSsrc(LOCAL_SSRC)
                .cname(CNAME)
                .clockRate(8000)
                .payloadTypes(RtpPayloadTypes.all())
                .jitterBuffer(jitter)
                .build();
    }

    private static RtpSession session() {
        return new RtpSession("rtcp-fields", sessionConfig());
    }

    private static void sendRtp(RtpSession session, int seq, long arrivalNanos) {
        byte[] raw = rtpPacket(0, seq, seq * 80L, REMOTE_SSRC, false, new byte[]{0});
        session.receive(raw, raw.length, arrivalNanos);
    }

    private static byte[] senderReport(int senderSsrc, long ntp) {
        SenderInfo info = new SenderInfo(ntp, 0L, 0L, 0L);
        return new RtcpPacketBuilder().buildSenderReport(senderSsrc, "sender@test",
                info, List.of());
    }

    @Test
    void rtcpIsNotCountedAsRtpRejectAndSessionKeepsProcessingRtp() {
        RtpSession session = session();
        long ntp = NtpTime.now();
        byte[] sr = senderReport(REMOTE_SSRC, ntp);
        session.receive(sr, sr.length, 1_000);

        // RTCP must never touch RTP parse-reject counters.
        assertEquals(0, session.counters().rejectedPackets());
        assertEquals(0L, session.rejectedRtcpPackets());

        // RTP on the same channel still flows after the RTCP datagram.
        sendRtp(session, 0, 2_000);
        sendRtp(session, 1, 3_000);
        assertEquals(2, session.ssrcState(REMOTE_SSRC).orElseThrow()
                .counters().receivedPackets());
    }

    @Test
    void malformedRtcpIsRejectedButDoesNotStopLaterValidPackets() {
        RtpSession session = session();
        byte[] broken = new byte[]{(byte) 0x80, (byte) RtcpMessage.PT_SR, 0, 3};
        session.receive(broken, broken.length, 1_000);
        byte[] wrongVersion = senderReport(REMOTE_SSRC, NtpTime.now());
        wrongVersion[0] = 0x00;
        session.receive(wrongVersion, wrongVersion.length, 2_000);
        assertEquals(2L, session.rejectedRtcpPackets());
        assertEquals(0, session.counters().rejectedPackets());

        // A later valid SR is still accepted and tracked.
        byte[] valid = senderReport(REMOTE_SSRC, NtpTime.now());
        session.receive(valid, valid.length, 3_000);
        assertEquals(2L, session.rejectedRtcpPackets());
        sendRtp(session, 0, 4_000);
        assertEquals(1, session.aggregateReceivedPackets());
    }

    @Test
    void rrBlockMatchesRealReceiveStatsAndCname() {
        RtpSession session = session();
        // Regular 20 ms spacing: transit deviation 0 -> zero jitter.
        long period = 20_000_000L;
        for (int seq = 0; seq < 10; seq++) {
            if (seq == 3 || seq == 7) {
                continue; // two losses
            }
            sendRtp(session, seq, seq * period);
        }

        byte[] compound = session.buildCurrentRtcpReport();
        List<RtcpMessage> messages = new RtcpPacketParser().parse(compound, compound.length);
        RtcpRr rr = (RtcpRr) messages.stream().filter(m -> m instanceof RtcpRr).findFirst()
                .orElseThrow();
        RtcpSdes sdes = (RtcpSdes) messages.stream().filter(m -> m instanceof RtcpSdes)
                .findFirst().orElseThrow();

        assertEquals(LOCAL_SSRC, rr.senderSsrc());
        assertEquals(1, rr.reportBlocks().size());
        ReportBlock block = rr.reportBlocks().get(0);
        assertEquals(REMOTE_SSRC, block.ssrc());
        assertEquals(2L, block.cumulativePacketsLost());
        assertEquals(9L, block.extendedHighestSequence());
        assertEquals(CNAME, sdes.cnames().get((long) LOCAL_SSRC));
        // First report covers the whole observed interval: 2 lost of 10 expected.
        int expectedFraction = (int) Math.min(255, 2L * 256 / 10);
        assertEquals(expectedFraction, block.fractionLost());
    }

    @Test
    void receivedSrBecomesLsrAndDlsrInNextRr() {
        java.util.concurrent.atomic.AtomicLong wall =
                new java.util.concurrent.atomic.AtomicLong(NtpTime.fromJavaMillis(30_000_000));
        RtpSession session = new RtpSession("rtcp-fields", sessionConfig(),
                com.github.guoyyit.rtpjitterbufferjava.engine.GlobalBufferLimiter.UNLIMITED,
                wall::get);
        sendRtp(session, 0, 1_000_000);

        // Non-zero fraction so the middle-32 LSR cannot be zero by coincidence.
        long srNtp = wall.get();
        byte[] sr = senderReport(REMOTE_SSRC, srNtp);
        session.receive(sr, sr.length, 100_000_000L);

        // Build the RR 80 ms (local wall time) after the SR.
        wall.set(NtpTime.fromJavaMillis(30_000_080));
        byte[] compound = session.buildCurrentRtcpReportAt(wall.get());
        List<RtcpMessage> messages = new RtcpPacketParser().parse(compound, compound.length);
        ReportBlock block = ((RtcpRr) messages.stream()
                .filter(m -> m instanceof RtcpRr).findFirst().orElseThrow())
                .reportBlocks().get(0);

        assertEquals(NtpTime.middle32(srNtp), block.lastSr());
        long expectedDlsr = Math.round(0.080 * 65536.0);
        assertEquals(expectedDlsr, block.delaySinceLastSr(), 1);
    }

    @Test
    void rtpRejectionCountersUnaffectedByRtcpTraffic() {
        RtpSession session = session();
        byte[] sr0 = senderReport(REMOTE_SSRC, NtpTime.now());
        session.receive(sr0, sr0.length, 1);
        byte[] badRtp = rtpPacket(0, 0, 0, REMOTE_SSRC, false, new byte[1]);
        session.receive(badRtp, 4, 2); // truncated RTP
        assertEquals(1, session.counters().rejectionBreakdown()
                .get(RtpRejectReason.TRUNCATED_PACKET));
        assertEquals(0L, session.rejectedRtcpPackets());
        assertFalse(session.terminated());
    }

    @Test
    void srFromUnknownSsrcStillTrackedForFutureBlock() {
        RtpSession session = session();
        long ntp = NtpTime.now();
        byte[] earlySr = senderReport(9999, ntp);
        session.receive(earlySr, earlySr.length, 1_000);
        // Once RTP from that SSRC exists, the block carries the remembered LSR.
        byte[] raw = rtpPacket(0, 0, 0, 9999, false, new byte[]{0});
        session.receive(raw, raw.length, 2_000);
        byte[] compound = session.buildCurrentRtcpReportAt(3_000_000_000L);
        List<RtcpMessage> messages = new RtcpPacketParser().parse(compound, compound.length);
        ReportBlock block = ((RtcpRr) messages.stream()
                .filter(m -> m instanceof RtcpRr).findFirst().orElseThrow())
                .reportBlocks().get(0);
        assertEquals(9999, block.ssrc());
        assertTrue(block.lastSr() != 0);
    }
}
