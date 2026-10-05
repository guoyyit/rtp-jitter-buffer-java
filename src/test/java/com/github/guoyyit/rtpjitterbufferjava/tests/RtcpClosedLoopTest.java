package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.engine.GlobalBufferLimiter;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.NtpTime;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.ReportBlock;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpMessage;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacketBuilder;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacketParser;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpReporter;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpRr;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpSdes;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.SenderInfo;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPayloadTypes;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSessionConfig;
import com.github.guoyyit.rtpjitterbufferjava.transport.InMemoryTransport;
import com.github.guoyyit.rtpjitterbufferjava.transport.PacketDatagram;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static com.github.guoyyit.rtpjitterbufferjava.tests.RtpPacketParserTest.rtpPacket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RTCP closed-loop behaviour: RTP/RTCP demux, report field consistency with
 * real receive statistics, and LSR/DLSR round-trip measurement.
 */
class RtcpClosedLoopTest {

    private static final long REMOTE_SSRC = 777;
    private static final long LOCAL_SSRC = 4321;

    /** Deterministic nanosecond clock for controlled RTT experiments. */
    private static final class ManualClock implements LongSupplier {
        private long nanos;

        @Override
        public long getAsLong() {
            return nanos;
        }

        void advanceMillis(long millis) {
            nanos += millis * 1_000_000L;
        }
    }

    private static RtpSessionConfig config(String cname) {
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(Duration.ofMillis(0))
                .initialDelay(Duration.ofMillis(10))
                .maxDelay(Duration.ofMillis(200))
                .build();
        return RtpSessionConfig.builder()
                .localSsrc((int) LOCAL_SSRC)
                .clockRate(8000)
                .payloadTypes(RtpPayloadTypes.all())
                .jitterBuffer(jitter)
                .rtcpInterval(Duration.ofMillis(30))
                .cname(cname)
                .build();
    }

    private static void sendRtp(RtpSession session, long ssrc, int seq, long arrivalNanos) {
        byte[] raw = rtpPacket(0, seq & 0xFFFF, (seq & 0xFFFF) * 160L, ssrc,
                false, new byte[]{(byte) seq});
        session.receive(raw, raw.length, arrivalNanos);
    }

    private static RtcpRr onlyRr(List<RtcpMessage> messages) {
        return (RtcpRr) messages.stream()
                .filter(RtcpRr.class::isInstance)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void rtcpIsDemuxedFromRtpAndNotCountedAsRtpRejection() {
        RtpSession session = new RtpSession("demux", config("demux@test"));
        sendRtp(session, REMOTE_SSRC, 0, 0);
        sendRtp(session, REMOTE_SSRC, 1, 20_000_000);

        byte[] sr = new RtcpPacketBuilder().buildSenderReport(999, "peer@test",
                new SenderInfo(NtpTime.now(), 0, 0, 0), List.of());
        session.receive(sr, sr.length, 40_000_000);

        sendRtp(session, REMOTE_SSRC, 2, 60_000_000);

        assertEquals(1, session.rtcpReceivedPackets());
        assertEquals(0, session.rtcpRejectedPackets());
        assertEquals(0, session.counters().rejectedPackets(),
                "RTCP must not be counted as RTP parse rejection");
        assertEquals(3, session.ssrcState(REMOTE_SSRC).orElseThrow()
                .counters().receivedPackets());
        session.close();
    }

    @Test
    void invalidRtcpIsRejectedWithoutDisturbingTheSession() {
        RtpSession session = new RtpSession("demux", config("demux@test"));

        // Declares 11 words (44 bytes) but the datagram carries only 8.
        byte[] truncated = {(byte) 0x80, (byte) 200, 0x00, 0x0A, 0, 0, 0, 1};
        session.receive(truncated, truncated.length, 0);
        assertEquals(1, session.rtcpRejectedPackets());
        assertEquals(0, session.rtcpReceivedPackets());

        // The session keeps handling subsequent valid RTCP and RTP datagrams.
        byte[] sr = new RtcpPacketBuilder().buildSenderReport(999, "peer@test",
                new SenderInfo(NtpTime.now(), 0, 0, 0), List.of());
        session.receive(sr, sr.length, 1_000_000);
        sendRtp(session, REMOTE_SSRC, 0, 2_000_000);

        assertEquals(1, session.rtcpReceivedPackets());
        assertEquals(1, session.rtcpRejectedPackets());
        assertEquals(1, session.ssrcState(REMOTE_SSRC).orElseThrow()
                .counters().receivedPackets());
        session.close();
    }

    @Test
    void reportBlocksReflectRealReceiveStatistics() {
        RtcpPacketParser parser = new RtcpPacketParser();
        RtpSession session = new RtpSession("reports", config("reports@test"));

        // Known arrival sequence: seq 3 and 7 lost, seq 5 duplicated.
        for (int seq = 0; seq < 10; seq++) {
            if (seq == 3 || seq == 7) {
                continue;
            }
            long arrival = seq * 20_000_000L + (seq % 4) * 5_000_000L;
            sendRtp(session, REMOTE_SSRC, seq, arrival);
            if (seq == 5) {
                sendRtp(session, REMOTE_SSRC, seq, arrival + 500_000);
            }
        }

        byte[] firstReport = session.buildRtcpReport();
        List<RtcpMessage> first = parser.parse(firstReport, firstReport.length);
        RtcpRr rr = onlyRr(first);
        assertEquals(LOCAL_SSRC, rr.senderSsrc());
        RtcpSdes sdes = (RtcpSdes) first.stream()
                .filter(RtcpSdes.class::isInstance).findFirst().orElseThrow();
        assertEquals("reports@test", sdes.cnames().get(LOCAL_SSRC));

        assertEquals(1, rr.reportBlocks().size());
        ReportBlock block = rr.reportBlocks().get(0);
        assertEquals(REMOTE_SSRC, block.ssrc());
        assertEquals(2, block.cumulativePacketsLost());
        assertEquals(9, block.extendedHighestSequence());
        assertEquals(0, block.fractionLost(), "first report has no interval baseline");
        long expectedJitter = session.ssrcState(REMOTE_SSRC).orElseThrow()
                .jitterBuffer().jitterNanos() * 8000 / 1_000_000_000L;
        assertEquals(expectedJitter, block.interarrivalJitter(),
                "jitter must be reported in RTP timestamp units");
        assertTrue(block.interarrivalJitter() > 0, "arrival jitter should be visible");

        // Interval loss: seq 12 of 10..13 never arrives -> 1/4 = 64/256.
        sendRtp(session, REMOTE_SSRC, 10, 200_000_000);
        sendRtp(session, REMOTE_SSRC, 11, 220_000_000);
        sendRtp(session, REMOTE_SSRC, 13, 260_000_000);
        byte[] secondReport = session.buildRtcpReport();
        ReportBlock second = onlyRr(parser.parse(secondReport, secondReport.length))
                .reportBlocks().get(0);
        assertEquals(64, second.fractionLost());
        assertEquals(3, second.cumulativePacketsLost());
        assertEquals(13, second.extendedHighestSequence());
        session.close();
    }

    @Test
    void lsrDlsrAndRoundTripMatchControlledExperiment() {
        RtcpPacketParser parser = new RtcpPacketParser();
        ManualClock clock = new ManualClock();
        RtpSession session = new RtpSession("b", config("b@test"),
                GlobalBufferLimiter.UNLIMITED, clock);
        sendRtp(session, REMOTE_SSRC, 0, clock.getAsLong());

        // Endpoint A sends an SR at manual time 0.
        long srNtp = session.currentNtp();
        byte[] srBytes = new RtcpPacketBuilder().buildSenderReport(REMOTE_SSRC,
                "a@test", new SenderInfo(srNtp, 0, 0, 0), List.of());

        // The SR takes 30 ms to reach B; B answers 15 ms later.
        clock.advanceMillis(30);
        session.receive(srBytes, srBytes.length, clock.getAsLong());
        clock.advanceMillis(15);
        byte[] rrBytes = session.buildRtcpReport();

        ReportBlock block = onlyRr(parser.parse(rrBytes, rrBytes.length))
                .reportBlocks().get(0);
        assertEquals(NtpTime.middle32(srNtp), block.lastSr(),
                "LSR must equal the middle 32 bits of the SR NTP timestamp");
        long expectedDlsr = Math.round(0.015 * 65536.0);
        long tolerance = Math.round(0.002 * 65536.0); // 2 ms
        assertTrue(Math.abs(block.delaySinceLastSr() - expectedDlsr) <= tolerance,
                "DLSR must match the 15 ms hold time: " + block.delaySinceLastSr());

        // The RR takes another 30 ms back to A: true round trip is 60 ms.
        clock.advanceMillis(30);
        long rttNanos = NtpTime.roundTripTimeNanos(session.currentNtp(),
                block.lastSr(), block.delaySinceLastSr());
        assertTrue(Math.abs(rttNanos - 60_000_000L) <= 2_000_000L,
                "computed RTT must match the 60 ms controlled round trip: " + rttNanos);
        session.close();
    }

    @Test
    void closedLoopOverInMemoryTransportProducesRoundTripTime() throws Exception {
        InMemoryTransport transportA = InMemoryTransport.pair(256);
        InMemoryTransport transportB = transportA.peer();
        RtcpPacketParser parser = new RtcpPacketParser();

        RtpSession sessionB = new RtpSession("b", config("b@test"));
        sessionB.startRtcpReporting(data -> transportB.send(data, data.length));

        // Endpoint A emits SRs periodically carrying the real NTP time.
        AtomicLong sentSrs = new AtomicLong();
        RtcpReporter reporterA = new RtcpReporter(new RtcpReporter.ReportSource() {
            @Override
            public long localSsrc() {
                return REMOTE_SSRC;
            }

            @Override
            public String cname() {
                return "a@test";
            }

            @Override
            public List<ReportBlock> reportBlocks() {
                return List.of();
            }

            @Override
            public SenderInfo senderInfo() {
                sentSrs.incrementAndGet();
                return new SenderInfo(NtpTime.now(), 0, 0, 0);
            }
        }, data -> transportA.send(data, data.length), Duration.ofMillis(30));
        reporterA.start();

        // A also sends a few media packets so B has reception statistics.
        for (int seq = 0; seq < 5; seq++) {
            byte[] raw = rtpPacket(0, seq, seq * 160L, REMOTE_SSRC, false, new byte[1]);
            transportA.send(raw, raw.length);
        }

        CopyOnWriteArrayList<Long> rttSamples = new CopyOnWriteArrayList<>();
        AtomicLong rrBlocksSeen = new AtomicLong();
        Thread pumpA = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    PacketDatagram datagram = transportA.receive(50, TimeUnit.MILLISECONDS);
                    if (datagram == null) {
                        continue;
                    }
                    for (RtcpMessage message : parser.parse(datagram.data(), datagram.length())) {
                        if (message instanceof RtcpRr rr) {
                            for (ReportBlock block : rr.reportBlocks()) {
                                rrBlocksSeen.incrementAndGet();
                                rttSamples.add(NtpTime.roundTripTimeNanos(NtpTime.now(),
                                        block.lastSr(), block.delaySinceLastSr()));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                Thread.currentThread().interrupt();
            }
        });
        pumpA.setDaemon(true);
        pumpA.start();

        long deadline = System.nanoTime() + 500_000_000L;
        while (System.nanoTime() < deadline) {
            PacketDatagram datagram = transportB.receive(50, TimeUnit.MILLISECONDS);
            if (datagram != null) {
                sessionB.receive(datagram.data(), datagram.length(), datagram.arrivalNanos());
            }
        }

        assertTrue(sentSrs.get() >= 2, "A should have sent several SRs: " + sentSrs.get());
        assertTrue(sessionB.rtcpReceivedPackets() >= 1,
                "B should have handled A's SRs as RTCP");
        assertEquals(0, sessionB.counters().rejectedPackets(),
                "RTCP on the shared channel must not pollute RTP rejections");
        long receivedByB = sessionB.ssrcState(REMOTE_SSRC).orElseThrow()
                .counters().receivedPackets();

        reporterA.stop();
        pumpA.interrupt();
        sessionB.close();
        transportA.close();
        transportB.close();

        assertEquals(5, receivedByB);
        assertTrue(rrBlocksSeen.get() >= 1, "A should have received report blocks");
        assertFalse(rttSamples.isEmpty());
        long bestRtt = rttSamples.stream().mapToLong(Long::longValue).min().orElseThrow();
        assertTrue(bestRtt <= 2_000_000L,
                "best RTT sample over an in-memory pipe must be within 2 ms: " + bestRtt);
        assertTrue(rttSamples.stream().allMatch(sample -> sample >= 0));
    }
}
