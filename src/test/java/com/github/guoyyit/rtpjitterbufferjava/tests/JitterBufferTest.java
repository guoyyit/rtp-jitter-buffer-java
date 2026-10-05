package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.AdmissionOutcome;
import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBuffer;
import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPacket;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPacketParser;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPayloadTypes;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.github.guoyyit.rtpjitterbufferjava.tests.RtpPacketParserTest.rtpPacket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JitterBufferTest {

    private static final RtpPacketParser PARSER = new RtpPacketParser(RtpPayloadTypes.all());

    private static JitterBuffer buffer() {
        JitterBufferConfig config = JitterBufferConfig.builder()
                .minDelay(Duration.ofMillis(0))
                .initialDelay(Duration.ofMillis(50))
                .maxDelay(Duration.ofMillis(200))
                .reorderWindowPackets(32)
                .maxPacketsPerSsrc(128)
                .build();
        return new JitterBuffer(1, config, 8000);
    }

    private static RtpPacket parsed(int seq, long rtpTs, long arrivalNanos) {
        byte[] raw = rtpPacket(0, seq, rtpTs, 1, false, new byte[]{(byte) seq});
        return PARSER.parse(raw, raw.length, arrivalNanos);
    }

    @Test
    void reordersPacketsWithinWindow() {
        JitterBuffer buffer = buffer();
        long t0 = 0;
        assertEquals(AdmissionOutcome.ACCEPTED, buffer.admit(parsed(0, 0, t0)));
        assertEquals(AdmissionOutcome.ACCEPTED, buffer.admit(parsed(2, 160, t0 + 20_000_000)));
        assertEquals(AdmissionOutcome.ACCEPTED, buffer.admit(parsed(1, 80, t0 + 10_000_000)));
        assertEquals(3, buffer.size());

        List<RtpPacket> ready = buffer.due(t0 + 100_000_000);
        assertEquals(3, ready.size());
        assertEquals(0, ready.get(0).header().sequenceNumber());
        assertEquals(1, ready.get(1).header().sequenceNumber());
        assertEquals(2, ready.get(2).header().sequenceNumber());
        assertEquals(0, buffer.size());
    }

    @Test
    void collapsesDuplicates() {
        JitterBuffer buffer = buffer();
        buffer.admit(parsed(5, 0, 0));
        assertEquals(AdmissionOutcome.DUPLICATE, buffer.admit(parsed(5, 0, 1_000)));
        assertEquals(1, buffer.size());
    }

    @Test
    void dropsLatePacketsAfterPlayout() {
        JitterBuffer buffer = buffer();
        buffer.admit(parsed(0, 0, 0));
        buffer.admit(parsed(1, 80, 20_000_000));
        buffer.due(100_000_000);
        assertEquals(AdmissionOutcome.LATE, buffer.admit(parsed(0, 0, 101_000_000)));
    }

    @Test
    void acceptsForwardJumpsRegardlessOfReorderWindow() {
        JitterBuffer buffer = buffer();
        buffer.admit(parsed(0, 0, 0));
        // A burst of 99 losses followed by seq 100 must still be admitted.
        assertEquals(AdmissionOutcome.ACCEPTED,
                buffer.admit(parsed(100, 8000, 1_000_000_000)));
        assertEquals(2, buffer.size());
    }

    @Test
    void rejectsBackwardPacketsBeyondReorderWindow() {
        JitterBuffer buffer = buffer();
        buffer.admit(parsed(0, 0, 0));
        buffer.due(100_000_000);
        // Burst loss: seq 100 resumes the stream after 99 missing packets.
        buffer.admit(parsed(100, 8000, 200_000_000));
        // seq 1 trails highest (100) by 99 > window (32): late, not reordered.
        assertEquals(AdmissionOutcome.REORDER_WINDOW_EXCEEDED,
                buffer.admit(parsed(1, 80, 210_000_000)));
        // seq 90 is inside the window behind and is still reordered.
        assertEquals(AdmissionOutcome.ACCEPTED,
                buffer.admit(parsed(90, 7200, 220_000_000)));
    }

    @Test
    void keepsOrderingAcrossSequenceWrap() {
        JitterBuffer buffer = buffer();
        buffer.admit(parsed(0xFFFE, 0, 0));
        buffer.admit(parsed(0, 160, 20_000_000));
        buffer.admit(parsed(0xFFFF, 80, 10_000_000));

        List<RtpPacket> ready = buffer.due(100_000_000);
        assertEquals(3, ready.size());
        assertEquals(0xFFFE, ready.get(0).header().sequenceNumber());
        assertEquals(0xFFFF, ready.get(1).header().sequenceNumber());
        assertEquals(0, ready.get(2).header().sequenceNumber());
    }

    @Test
    void enforcesPerSsrcPacketCap() {
        JitterBufferConfig config = JitterBufferConfig.builder()
                .minDelay(Duration.ofSeconds(10))
                .initialDelay(Duration.ofSeconds(10))
                .maxDelay(Duration.ofSeconds(20))
                .reorderWindowPackets(1000)
                .maxPacketsPerSsrc(3)
                .build();
        JitterBuffer buffer = new JitterBuffer(1, config, 8000);
        assertEquals(AdmissionOutcome.ACCEPTED, buffer.admit(parsed(0, 0, 0)));
        assertEquals(AdmissionOutcome.ACCEPTED, buffer.admit(parsed(1, 80, 1_000_000)));
        assertEquals(AdmissionOutcome.ACCEPTED, buffer.admit(parsed(2, 160, 2_000_000)));
        assertEquals(AdmissionOutcome.BUFFER_FULL, buffer.admit(parsed(3, 240, 3_000_000)));
    }

    @Test
    void delayStaysWithinConfiguredBounds() {
        JitterBuffer buffer = buffer();
        for (int i = 0; i < 200; i++) {
            long jitter = (i % 5) * 5_000_000L;
            buffer.admit(parsed(i + 1, (i + 1) * 80L, i * 20_000_000L + jitter));
        }
        assertTrue(buffer.currentDelayNanos() <= Duration.ofMillis(200).toNanos());
        assertTrue(buffer.currentDelayNanos() >= 0);
        assertTrue(buffer.jitterNanos() > 0);
    }
}
