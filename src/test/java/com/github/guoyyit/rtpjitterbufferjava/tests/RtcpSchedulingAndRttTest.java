package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.NtpTime;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.ReportBlock;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpMessage;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacketBuilder;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacketParser;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpRr;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpReporter;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Periodic compound RTCP, cadence reconfiguration, failure recovery and an
 * SR -> RR -> RTT closed loop.
 */
class RtcpSchedulingAndRttTest {

    private static RtpSession session(Duration interval) {
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(Duration.ofMillis(0))
                .initialDelay(Duration.ofMillis(5))
                .maxDelay(Duration.ofMillis(100))
                .build();
        RtpSessionConfig config = RtpSessionConfig.builder()
                .localSsrc(1010).cname("scheduler@test")
                .rtcpInterval(interval)
                .payloadTypes(RtpPayloadTypes.all())
                .jitterBuffer(jitter)
                .build();
        return new RtpSession("sched", config);
    }

    @Test
    void sendsCompoundReportsAtConfiguredCadence() throws Exception {
        CopyOnWriteArrayList<byte[]> sent = new CopyOnWriteArrayList<>();
        RtpSession session = session(Duration.ofMillis(40));
        session.startRtcpReporting(sent::add);
        Thread.sleep(200);
        session.stopRtcpReporting();
        assertTrue(sent.size() >= 3, "expected periodic compound reports: " + sent.size());
        // Every datagram must parse as RTCP and contain an SDES CNAME chunk.
        for (byte[] data : sent) {
            List<RtcpMessage> messages = new RtcpPacketParser().parse(data, data.length);
            assertTrue(messages.stream().anyMatch(m -> m instanceof RtcpRr));
        }
    }

    @Test
    void changingIntervalChangesCadence() throws Exception {
        CopyOnWriteArrayList<byte[]> sent = new CopyOnWriteArrayList<>();
        RtpSession session = session(Duration.ofMillis(500));
        session.startRtcpReporting(sent::add);
        Thread.sleep(120);
        int before = sent.size();
        session.setRtcpInterval(Duration.ofMillis(30));
        Thread.sleep(200);
        int after = sent.size();
        session.stopRtcpReporting();
        assertTrue(before <= 1, "slow cadence should send ~nothing: " + before);
        assertTrue(after - before >= 3, "fast cadence should resume: " + (after - before));
    }

    @Test
    void sinkFailuresDoNotStopSchedulerOrMediaAndRecoveryResumes() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean fail =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        RtcpReporter.RtcpSink sink = data -> {
            if (fail.get()) {
                failures.incrementAndGet();
                throw new IOException("transport down");
            }
            successes.incrementAndGet();
        };
        RtpSession session = session(Duration.ofMillis(20));
        session.startRtcpReporting(sink);
        Thread.sleep(120);
        assertTrue(failures.get() >= 3, "attempts continue while failing: " + failures.get());

        fail.set(false);
        Thread.sleep(120);
        assertTrue(successes.get() >= 2, "reporting auto-resumes after recovery: "
                + successes.get());
        session.stopRtcpReporting();
        assertFalse(session.rtcpReportingRunning());
    }

    @Test
    void closeStopsReporterAndReleasesThread() throws Exception {
        RtpSession session = session(Duration.ofMillis(20));
        CopyOnWriteArrayList<byte[]> sent = new CopyOnWriteArrayList<>();
        session.startRtcpReporting(sent::add);
        assertTrue(session.rtcpReportingRunning());
        session.close();
        int countAtClose = sent.size();
        Thread.sleep(100);
        assertEquals(countAtClose, sent.size(), "no reports after close");
        assertFalse(session.rtcpReportingRunning());

        // Reporter thread must have terminated.
        Thread[] threads = new Thread[Thread.activeCount() * 2 + 8];
        int n = Thread.enumerate(threads);
        for (int i = 0; i < n; i++) {
            assertFalse(threads[i].getName().contains("rtcp-reporter-1010"),
                    "reporter thread still alive: " + threads[i].getName());
        }
    }

    @Test
    void roundTripTimeFromControlledSrRrExchangeMatchesTrueRtt() {
        // Controlled local clocks: monotonic (arrival) and wall NTP.
        java.util.concurrent.atomic.AtomicLong wall =
                new java.util.concurrent.atomic.AtomicLong(0L);
        RtpSessionConfig cfg = RtpSessionConfig.builder()
                .localSsrc(1010).cname("scheduler@test")
                .rtcpInterval(Duration.ofSeconds(5))
                .payloadTypes(RtpPayloadTypes.all())
                .jitterBuffer(JitterBufferConfig.builder()
                        .minDelay(Duration.ZERO).initialDelay(Duration.ofMillis(1))
                        .maxDelay(Duration.ofMillis(100)).build())
                .build();
        RtpSession receiver = new RtpSession("rtt", cfg,
                com.github.guoyyit.rtpjitterbufferjava.engine.GlobalBufferLimiter.UNLIMITED,
                wall::get);

        byte[] rtp = RtpPacketParserTest.rtpPacket(0, 0, 0, 5050, false, new byte[]{0});
        receiver.receive(rtp, rtp.length, 1_000);

        // SR arrives at local wall time W.
        long srArrivalWall = NtpTime.fromJavaMillis(20_000_000);
        // The SR's NTP timestamp matches the receiver wall clock (assumes
        // synchronized clocks; the experiment controls them to be identical).
        long srNtp = srArrivalWall;
        byte[] sr = new RtcpPacketBuilder().buildSenderReport(5050, "sender@test",
                new SenderInfo(srNtp, 0L, 0L, 0L), List.of());
        wall.set(srArrivalWall);
        receiver.receive(sr, sr.length, 200_000_000L);

        // RR is sent 80 ms later (receiver's measured delay).
        long rrSendWall = NtpTime.fromJavaMillis(20_000_000 + 80);
        wall.set(rrSendWall);
        byte[] rrCompound = receiver.buildCurrentRtcpReportAt(rrSendWall);

        // RR reaches the sender another 20 ms later -> total A-LSR = 100 ms,
        // DLSR = 80 ms, hence true RTT = 20 ms.
        long rrArrivalWall = NtpTime.fromJavaMillis(20_000_000 + 100);
        List<RtcpMessage> messages = new RtcpPacketParser().parse(rrCompound, rrCompound.length);
        ReportBlock block = ((RtcpRr) messages.stream()
                .filter(m -> m instanceof RtcpRr).findFirst().orElseThrow())
                .reportBlocks().get(0);

        assertEquals(NtpTime.middle32(srNtp), block.lastSr());
        long expectedDlsr = Math.round(0.080 * 65536.0);
        assertEquals(expectedDlsr, block.delaySinceLastSr(), 1);

        long rttNanos = NtpTime.roundTripTimeNanos(
                rrArrivalWall, block.lastSr(), block.delaySinceLastSr());
        assertEquals(20_000_000L, rttNanos, 2_000_000L);
    }
}
