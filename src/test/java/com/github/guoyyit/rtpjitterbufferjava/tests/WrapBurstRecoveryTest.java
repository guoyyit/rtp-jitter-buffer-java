package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
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
 * Burst loss that straddles the 16-bit sequence-number wrap must recover the
 * same way as a non-wrapping burst, with correct extended stats.
 */
class WrapBurstRecoveryTest {

    private static final int REMOTE_SSRC = 222;

    private static RtpSession session() {
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(Duration.ofMillis(0))
                .initialDelay(Duration.ofMillis(5))
                .maxDelay(Duration.ofMillis(100))
                .reorderWindowPackets(64)
                .build();
        RtpSessionConfig config = RtpSessionConfig.builder()
                .localSsrc(Integer.MAX_VALUE)
                .payloadTypes(RtpPayloadTypes.all())
                .jitterBuffer(jitter)
                .build();
        return new RtpSession("wrap-burst", config);
    }

    private static void send(RtpSession session, int seq, long arrivalIndex) {
        byte[] raw = rtpPacket(0, seq, (seq & 0xFFFFL) * 80L, REMOTE_SSRC,
                false, new byte[]{(byte) seq});
        session.receive(raw, raw.length, arrivalIndex * 20_000_000L);
    }

    @Test
    void recoversAcrossWrapAndCountsExtendedLoss() {
        RtpSession session = session();

        send(session, 0xFFF0, 0);
        send(session, 0xFFF1, 1);
        session.pollReady(2_000_000_000L);

        // Lose 0xFFF2..0x0032 (0x41 = 65 packets); resume at 0x0033 after wrap.
        int resume = 0x0033;
        send(session, resume, 100);
        send(session, resume + 1, 101);
        send(session, resume + 2, 102);

        List<Integer> played = new ArrayList<>();
        session.pollReady(200_000_000_000L).forEach(p ->
                played.add(p.header().sequenceNumber()));
        long previousExtended = -1L;
        for (int seq : played) {
            long extended = com.github.guoyyit.rtpjitterbufferjava.rtp.SeqNum
                    .unwrap(previousExtended < 0 ? 0xFFF0L : previousExtended, seq);
            assertTrue(previousExtended < 0 || extended == previousExtended + 1,
                    "playout must advance by one extended seq: " + played);
            previousExtended = extended;
        }
        assertTrue(played.contains(resume), "resumed post-wrap packet plays");

        ReceiveCounters counters = session.ssrcState(REMOTE_SSRC).orElseThrow().counters();
        // Extended highest: base 0xFFF0 (65520), resume+2 = 0x10035.
        assertEquals(0x10035L, counters.extendedHighestSequence());
        // Unique received: 0xFFF0, 0xFFF1, 0x0033, 0x0034, 0x0035 = 5.
        // Expected interval 0xFFF0..0x10035 = 0x46 = 70 packets -> 65 lost.
        assertEquals(65, counters.cumulativeLost());
        assertTrue(counters.cumulativeLost() >= 0);
    }
}
