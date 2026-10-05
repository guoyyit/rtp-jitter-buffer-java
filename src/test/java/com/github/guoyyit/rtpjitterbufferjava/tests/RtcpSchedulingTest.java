package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.engine.RtpReceiver;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPayloadTypes;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSessionConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.guoyyit.rtpjitterbufferjava.tests.RtpPacketParserTest.rtpPacket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Periodic RTCP scheduling: configured cadence, non-blocking failure
 * semantics, clean shutdown and multi-session isolation.
 */
class RtcpSchedulingTest {

    private static RtpSessionConfig config(Duration rtcpInterval) {
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(Duration.ofMillis(0))
                .initialDelay(Duration.ofMillis(10))
                .maxDelay(Duration.ofMillis(200))
                .build();
        return RtpSessionConfig.builder()
                .localSsrc(4321)
                .payloadTypes(RtpPayloadTypes.all())
                .jitterBuffer(jitter)
                .rtcpInterval(rtcpInterval)
                .cname("sched@test")
                .build();
    }

    private static void sendRtp(RtpSession session, int ssrc, int seq, long arrivalNanos) {
        byte[] raw = rtpPacket(0, seq & 0xFFFF, (seq & 0xFFFF) * 160L, ssrc,
                false, new byte[]{(byte) seq});
        session.receive(raw, raw.length, arrivalNanos);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void rtcpIntervalControlsSendCadence() {
        AtomicInteger fastSends = new AtomicInteger();
        AtomicInteger slowSends = new AtomicInteger();
        RtpSession fast = new RtpSession("fast", config(Duration.ofMillis(40)));
        RtpSession slow = new RtpSession("slow", config(Duration.ofMillis(160)));
        fast.startRtcpReporting(data -> fastSends.incrementAndGet());
        slow.startRtcpReporting(data -> slowSends.incrementAndGet());

        sleep(400);
        fast.close();
        slow.close();

        assertTrue(fastSends.get() >= 4, "fast session should report often: " + fastSends.get());
        assertTrue(slowSends.get() >= 1, "slow session should still report: " + slowSends.get());
        assertTrue(fastSends.get() > slowSends.get() * 2,
                "cadence must follow rtp.rtcp-interval: fast=" + fastSends.get()
                        + " slow=" + slowSends.get());
    }

    @Test
    void sendFailuresNeitherStopReportingNorBlockMedia() {
        AtomicInteger attempted = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();
        AtomicBoolean failing = new AtomicBoolean(true);
        RtpSession session = new RtpSession("flaky", config(Duration.ofMillis(30)));
        session.startRtcpReporting(data -> {
            attempted.incrementAndGet();
            if (failing.get()) {
                throw new IOException("simulated transport outage");
            }
            delivered.incrementAndGet();
        });

        // Media keeps flowing while every RTCP send fails.
        for (int seq = 0; seq < 5; seq++) {
            sendRtp(session, 777, seq, seq * 20_000_000L);
        }
        sleep(150);
        assertTrue(attempted.get() >= 2, "reporter must keep trying: " + attempted.get());
        assertEquals(0, delivered.get());
        assertTrue(session.rtcpReporting(), "periodic task must survive send failures");
        assertEquals(5, session.ssrcState(777).orElseThrow().counters().receivedPackets());
        assertEquals(5, session.pollReady(600_000_000_000L).size(),
                "media playout must not be blocked by RTCP failures");

        // The channel recovers: periodic sending resumes automatically.
        failing.set(false);
        sleep(150);
        assertTrue(delivered.get() >= 1, "sending must resume after recovery");
        session.close();
    }

    @Test
    void closeStopsReportingAndReleasesResources() {
        AtomicInteger sends = new AtomicInteger();
        RtpSession session = new RtpSession("closing", config(Duration.ofMillis(30)));
        session.startRtcpReporting(data -> sends.incrementAndGet());
        sleep(120);
        assertTrue(session.rtcpReporting());
        assertTrue(sends.get() >= 1);

        session.close();
        assertFalse(session.rtcpReporting(), "close must stop the periodic task");
        int afterClose = sends.get();
        sleep(120);
        assertEquals(afterClose, sends.get(), "no sends may happen after close");
    }

    @Test
    void rtcpProblemsAreIsolatedBetweenSessions() {
        RtpReceiver receiver = new RtpReceiver(10_000);
        RtpSession broken = receiver.createSession("broken", config(Duration.ofMillis(30)));
        RtpSession healthy = receiver.createSession("healthy", config(Duration.ofMillis(30)));

        AtomicInteger brokenAttempts = new AtomicInteger();
        AtomicInteger healthySends = new AtomicInteger();
        broken.startRtcpReporting(data -> {
            brokenAttempts.incrementAndGet();
            throw new IOException("permanently broken channel");
        });
        healthy.startRtcpReporting(data -> healthySends.incrementAndGet());

        // Broken session also receives malformed RTCP; healthy session gets media.
        byte[] truncated = {(byte) 0x80, (byte) 201, 0x00, 0x0F, 0, 0, 0, 1};
        broken.receive(truncated, truncated.length, 0);
        for (int seq = 0; seq < 4; seq++) {
            sendRtp(healthy, 555, seq, seq * 20_000_000L);
        }
        sleep(200);

        assertTrue(brokenAttempts.get() >= 2, "broken session keeps attempting");
        assertTrue(healthySends.get() >= 2,
                "healthy session reporting is unaffected: " + healthySends.get());
        assertEquals(1, broken.rtcpRejectedPackets());
        assertEquals(0, healthy.rtcpRejectedPackets());
        assertEquals(4, healthy.ssrcState(555).orElseThrow().counters().receivedPackets());
        assertEquals(4, healthy.pollReady(600_000_000_000L).size());
        receiver.close();
        assertFalse(broken.rtcpReporting());
        assertFalse(healthy.rtcpReporting());
    }
}
