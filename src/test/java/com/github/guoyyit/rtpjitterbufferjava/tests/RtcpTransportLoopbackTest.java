package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.NtpTime;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacketBuilder;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.SenderInfo;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPayloadTypes;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSessionConfig;
import com.github.guoyyit.rtpjitterbufferjava.transport.InMemoryTransport;
import com.github.guoyyit.rtpjitterbufferjava.transport.PacketDatagram;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.github.guoyyit.rtpjitterbufferjava.tests.RtpPacketParserTest.rtpPacket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RTP and RTCP share one transport pair end to end: media is unaffected by
 * RTCP traffic and a periodic RR answers a peer SR.
 */
class RtcpTransportLoopbackTest {

    private static RtpSessionConfig config(int localSsrc) {
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(Duration.ofMillis(0))
                .initialDelay(Duration.ofMillis(5))
                .maxDelay(Duration.ofMillis(100))
                .reorderWindowPackets(64)
                .build();
        return RtpSessionConfig.builder()
                .localSsrc(localSsrc)
                .cname("loop@" + localSsrc)
                .rtcpInterval(Duration.ofMillis(40))
                .payloadTypes(RtpPayloadTypes.all())
                .jitterBuffer(jitter)
                .build();
    }

    @Test
    void mixedRtpAndRtcpOnOneChannelAndPeriodicRrAnswersSr() throws Exception {
        InMemoryTransport senderSide = InMemoryTransport.pair(256);
        InMemoryTransport receiverSide = senderSide.peer();

        RtpSession receiver = new RtpSession("loop", config(3030));
        java.util.List<byte[]> reportsFromReceiver = new java.util.concurrent.CopyOnWriteArrayList<>();
        // Reporter writes back over the receiver's transport toward the sender.
        receiver.startRtcpReporting(data -> receiverSide.send(data, data.length));

        // Sender transmits media and one SR over the same channel.
        for (int seq = 0; seq < 5; seq++) {
            byte[] rtp = rtpPacket(0, seq, seq * 80L, 4040, false, new byte[]{0});
            senderSide.send(rtp, rtp.length);
        }
        long srNtp = NtpTime.now();
        byte[] sr = new RtcpPacketBuilder().buildSenderReport(4040, "sender@loop",
                new SenderInfo(srNtp, 0, 5, 5), List.of());
        senderSide.send(sr, sr.length);

        int rtpSeen = 0;
        long deadline = System.nanoTime() + Duration.ofMillis(400).toNanos();
        PacketDatagram datagram;
        while ((datagram = receiverSide.receive(50, TimeUnit.MILLISECONDS)) != null) {
            receiver.receive(datagram.data(), datagram.length(), System.nanoTime());
            // Forward every receiver RR back to a capture sink via the pair:
            // here the "sender" drains the reverse direction.
            PacketDatagram reverse;
            while ((reverse = senderSide.receive(5, TimeUnit.MILLISECONDS)) != null) {
                reportsFromReceiver.add(reverse.data());
            }
            rtpSeen++;
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        // Draining loop: give periodic reports time to arrive.
        long drainUntil = System.nanoTime() + Duration.ofMillis(250).toNanos();
        PacketDatagram tail;
        while (System.nanoTime() < drainUntil
                && (tail = senderSide.receive(50, TimeUnit.MILLISECONDS)) != null) {
            reportsFromReceiver.add(tail.data());
        }

        assertEquals(6, rtpSeen, "all 5 RTP + 1 SR delivered on one channel");
        assertEquals(5, receiver.ssrcState(4040).orElseThrow().counters().receivedPackets());
        assertEquals(0, receiver.counters().rejectedPackets());
        assertEquals(0L, receiver.rejectedRtcpPackets());
        assertTrue(reportsFromReceiver.size() >= 1, "sender received at least one compound RR");

        // Every captured datagram must parse as RTCP (never RTP).
        com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacketParser rtcpParser =
                new com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacketParser();
        for (byte[] data : reportsFromReceiver) {
            var messages = rtcpParser.parse(data, data.length);
            assertTrue(messages.stream().anyMatch(m ->
                    m instanceof com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpRr));
        }

        receiver.close();
        senderSide.close();
        receiverSide.close();
    }
}
