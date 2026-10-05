package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.engine.GlobalBufferLimiter;
import com.github.guoyyit.rtpjitterbufferjava.engine.RtpReceiver;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.NtpTime;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacketBuilder;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.SenderInfo;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPayloadTypes;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSessionConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.guoyyit.rtpjitterbufferjava.tests.RtpPacketParserTest.rtpPacket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent RTCP interaction and burst recovery across concurrent sessions,
 * plus single-SSRC/session/global buffer bounds during recovery.
 */
class RtcpMultiSessionIsolationTest {

    private static RtpSessionConfig config(int localSsrc, String cname) {
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(Duration.ofMillis(0))
                .initialDelay(Duration.ofMillis(5))
                .maxDelay(Duration.ofMillis(100))
                .reorderWindowPackets(64)
                .maxPacketsPerSsrc(8)
                .build();
        return RtpSessionConfig.builder()
                .localSsrc(localSsrc)
                .cname(cname)
                .rtcpInterval(Duration.ofMillis(25))
                .payloadTypes(RtpPayloadTypes.all())
                .jitterBuffer(jitter)
                .maxPacketsPerSession(16)
                .build();
    }

    private static void sendRtp(RtpSession session, int ssrc, int seq, long nanos) {
        byte[] raw = rtpPacket(0, seq, seq * 80L, ssrc, false, new byte[]{0});
        session.receive(raw, raw.length, nanos);
    }

    private static byte[] sr(int ssrc) {
        return new RtcpPacketBuilder().buildSenderReport(ssrc, "p" + ssrc,
                new SenderInfo(NtpTime.now(), 0, 0, 0), List.of());
    }

    @Test
    void rtcpFailuresInOneSessionDoNotAffectOthers() throws Exception {
        RtpReceiver receiver = new RtpReceiver(64);
        RtpSession a = receiver.createSession("a", config(100, "a@x"));
        RtpSession b = receiver.createSession("b", config(200, "b@x"));

        AtomicInteger aAttempts = new AtomicInteger();
        // Session A sink always fails; session B sink records everything.
        CopyOnWriteArrayList<byte[]> bSent = new CopyOnWriteArrayList<>();
        a.startRtcpReporting(data -> {
            aAttempts.incrementAndGet();
            throw new IOException("a channel down");
        });
        b.startRtcpReporting(bSent::add);

        sendRtp(a, 1, 0, 1);
        sendRtp(b, 2, 0, 1);
        Thread.sleep(150);

        assertTrue(aAttempts.get() >= 2, "A keeps retrying through failures");
        assertTrue(bSent.size() >= 2, "B reporting unaffected by A failure");

        // Media paths stay healthy in both sessions.
        for (int seq = 100; seq < 120; seq++) {
            sendRtp(a, 1, seq, seq * 1_000_000L);
            sendRtp(b, 2, seq, seq * 1_000_000L);
        }
        assertEquals(21, a.ssrcState(1).orElseThrow().counters().receivedPackets());
        assertEquals(21, b.ssrcState(2).orElseThrow().counters().receivedPackets());
        // A's RTCP problems never leak into B's rejection counters.
        assertEquals(0L, b.rejectedRtcpPackets());

        receiver.close();
        assertFalse_reporterStopped(a);
        assertFalse_reporterStopped(b);
    }

    private static void assertFalse_reporterStopped(RtpSession session) {
        assertEquals(false, session.rtcpReportingRunning());
    }

    @Test
    void malformedRtcpInOneSessionIsLocalAndRecoveryIndependent() {
        RtpReceiver receiver = new RtpReceiver(64);
        RtpSession a = receiver.createSession("a", config(100, "a@x"));
        RtpSession b = receiver.createSession("b", config(200, "b@x"));

        byte[] broken = new byte[]{(byte) 0x80, (byte) 200, 0, 9};
        a.receive(broken, broken.length, 1);
        a.receive(sr(777), 4, 2); // truncated valid SR -> rejected
        assertEquals(2L, a.rejectedRtcpPackets());
        assertEquals(0L, b.rejectedRtcpPackets());

        // A still accepts a later valid SR and RTP; B untouched.
        byte[] validSr = sr(777);
        a.receive(validSr, validSr.length, 3);
        sendRtp(a, 777, 0, 4);
        sendRtp(b, 888, 0, 5);
        assertEquals(2L, a.rejectedRtcpPackets());
        assertEquals(1, a.aggregateReceivedPackets());
        assertEquals(1, b.aggregateReceivedPackets());
        receiver.close();
    }

    @Test
    void burstRecoveryRespectsPerSsrcSessionAndGlobalBounds() {
        RtpReceiver receiver = new RtpReceiver(6);
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(Duration.ofSeconds(10))
                .initialDelay(Duration.ofSeconds(10))
                .maxDelay(Duration.ofSeconds(30))
                .reorderWindowPackets(64)
                .maxPacketsPerSsrc(4)
                .build();
        RtpSessionConfig cfg = RtpSessionConfig.builder()
                .localSsrc(9).payloadTypes(RtpPayloadTypes.all())
                .jitterBuffer(jitter).maxPacketsPerSession(5).build();
        RtpSession session = receiver.createSession("bounded", cfg);

        // Large forward jump, then a flood: buffering must stay bounded even
        // though the jump itself is never rejected.
        sendRtp(session, 500, 0, 1);
        for (int seq = 1000; seq < 1050; seq++) {
            sendRtp(session, 500, seq, seq * 1_000_000L);
        }
        assertTrue(session.totalBufferedPackets() <= 5,
                "per-session cap holds: " + session.totalBufferedPackets());
        assertTrue(receiver.totalBufferedPackets() <= 6,
                "global cap holds: " + receiver.totalBufferedPackets());
        assertTrue(session.aggregateOverloadDroppedPackets() > 0);
        // The resume packet's jump is recovered (not a late/window drop).
        assertEquals(0, session.aggregateLateDroppedPackets());
        receiver.close();
        assertEquals(0, receiver.totalBufferedPackets());
    }
}
