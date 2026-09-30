package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.engine.RtpReceiver;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPayloadTypes;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSessionConfig;
import com.github.guoyyit.rtpjitterbufferjava.transport.InMemoryTransport;
import com.github.guoyyit.rtpjitterbufferjava.transport.PacketDatagram;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.github.guoyyit.rtpjitterbufferjava.tests.RtpPacketParserTest.rtpPacket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a full sender -> in-memory pipe -> RtpReceiver -> playout pipeline
 * with reordering, loss and arrival jitter.
 */
class EndToEndPipelineTest {

    private static RtpSessionConfig config() {
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(Duration.ofMillis(0))
                .initialDelay(Duration.ofMillis(40))
                .maxDelay(Duration.ofMillis(150))
                .reorderWindowPackets(64)
                .build();
        return RtpSessionConfig.builder()
                .localSsrc(Integer.MAX_VALUE)
                .payloadTypes(RtpPayloadTypes.all())
                .jitterBuffer(jitter)
                .build();
    }

    @Test
    void pipelineReordersAndPlaysInSequence() throws Exception {
        RtpReceiver receiver = new RtpReceiver(4096);
        RtpSession session = receiver.createSession("call", config());
        InMemoryTransport senderTransport = InMemoryTransport.pair(256);
        InMemoryTransport receiverTransport = senderTransport.peer();

        int total = 60;
        List<Integer> sendOrder = new ArrayList<>();
        for (int seq = 0; seq < total; seq++) {
            if (seq % 11 == 4) {
                continue; // drop some packets to simulate loss
            }
            sendOrder.add(seq);
        }
        // swap a few adjacent packets to simulate reordering
        for (int i = 1; i + 1 < sendOrder.size(); i += 7) {
            Integer tmp = sendOrder.get(i);
            sendOrder.set(i, sendOrder.get(i + 1));
            sendOrder.set(i + 1, tmp);
        }

        long baseArrival = System.nanoTime();
        for (int index = 0; index < sendOrder.size(); index++) {
            int seq = sendOrder.get(index);
            long jitterMs = (index % 4) * 4L;
            long arrival = baseArrival + index * 20_000_000L + Duration.ofMillis(jitterMs).toNanos();
            byte[] raw = rtpPacket(0, seq, seq * 80L, 555, false, new byte[]{(byte) seq});
            senderTransport.send(raw, raw.length);
            PacketDatagram datagram = receiverTransport.receive(2, TimeUnit.SECONDS);
            session.receive(datagram.data(), datagram.length(), arrival);
        }

        long playoutDeadline = baseArrival + Duration.ofSeconds(5).toNanos();
        List<Integer> played = new ArrayList<>();
        session.pollReady(playoutDeadline).forEach(packet ->
                played.add(packet.header().sequenceNumber()));

        // Output must be strictly increasing even though input was reordered.
        for (int i = 1; i < played.size(); i++) {
            assertTrue(played.get(i) > played.get(i - 1),
                    "playout must be ordered: " + played);
        }
        assertEquals(sendOrder.size(), played.size(),
                "every accepted packet should eventually play");
        assertTrue(session.ssrcState(555).orElseThrow().counters().cumulativeLost() > 0,
                "lost packets must be reflected in counters");
        receiver.close();
    }
}
