package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.GlobalBufferBudget;
import com.github.guoyyit.rtpjitterbufferjava.clock.SystemClock;
import com.github.guoyyit.rtpjitterbufferjava.codec.PayloadTypeRegistry;
import com.github.guoyyit.rtpjitterbufferjava.config.BufferProperties;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import com.github.guoyyit.rtpjitterbufferjava.transport.UdpLoopbackTransport;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpLoopbackIntegrationTest {

    @Test
    void receivesRtpAndRepliesWithRtcpOverLoopback() throws Exception {
        int rtpPort = 28000;
        int rtcpPort = 28001;
        AtomicInteger received = new AtomicInteger();

        BufferProperties props = BufferProperties.defaults();
        RtpSession session = new RtpSession("udp", 4242L, props,
                new PayloadTypeRegistry(Set.of(0), 8000),
                new GlobalBufferBudget(GlobalBufferBudget.UNLIMITED), SystemClock.INSTANCE);

        UdpLoopbackTransport transport = new UdpLoopbackTransport(rtpPort, rtcpPort);
        transport.start(
                (data, offset, length, source) -> {
                    session.onRtp(data, offset, length, source);
                    received.incrementAndGet();
                },
                (data, offset, length, source) -> session.onRtcp(data, offset, length, source));

        try (DatagramSocket sender = new DatagramSocket()) {
            InetSocketAddress rtpTarget = new InetSocketAddress("127.0.0.1", rtpPort);
            for (int seq = 0; seq < 5; seq++) {
                byte[] packet = RtpTestPackets.rtp(0, seq, seq * 160L, 777L, new byte[] {1, 2});
                sender.send(new DatagramPacket(packet, packet.length, rtpTarget));
            }

            waitUntil(() -> received.get() >= 5);

            byte[] rr = session.buildRtcpReport();
            sender.send(new DatagramPacket(rr, rr.length,
                    new InetSocketAddress("127.0.0.1", rtcpPort)));

            assertTrue(session.getReceptionStats().size() >= 1);
        } finally {
            transport.close();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("condition was not met within timeout");
    }
}
