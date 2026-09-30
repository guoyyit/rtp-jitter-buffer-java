package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpScheduler;
import com.github.guoyyit.rtpjitterbufferjava.transport.InMemoryTransport;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RtcpSchedulerTest {

    @Test
    void sendsReportsPeriodicallyWithoutBlocking() throws Exception {
        AtomicInteger reports = new AtomicInteger();
        InMemoryTransport transport = new InMemoryTransport("tx");
        InMemoryTransport sink = new InMemoryTransport("rx");
        transport.start(null,null);
        InMemoryTransport.pair(transport, sink,
                new InetSocketAddress("127.0.0.1", 1),
                new InetSocketAddress("127.0.0.1", 2));

        sink.start(
                (data, offset, length, source) -> { },
                (data, offset, length, source) -> reports.incrementAndGet());

        RtcpScheduler scheduler = new RtcpScheduler("t", 20,
                () -> RtpTestPackets.receiverReport(1L, new long[0][]));
        scheduler.start(transport, new InetSocketAddress("127.0.0.1", 2));

        Thread.sleep(120);
        scheduler.stop();

        assertTrue(reports.get() >= 3,
                "expected several periodic RTCP reports, got " + reports.get());
    }

    @Test
    void keepsRunningWhenReportBuildingFails() throws Exception {
        AtomicInteger reports = new AtomicInteger();
        InMemoryTransport transport = new InMemoryTransport("tx2");
        InMemoryTransport sink = new InMemoryTransport("rx2");
        transport.start(null,null);
        InMemoryTransport.pair(transport, sink,
                new InetSocketAddress("127.0.0.1", 3),
                new InetSocketAddress("127.0.0.1", 4));

        sink.start(
                (data, offset, length, source) -> { },
                (data, offset, length, source) -> reports.incrementAndGet());

        AtomicInteger calls = new AtomicInteger();
        RtcpScheduler scheduler = new RtcpScheduler("t2", 20, () -> {
            if (calls.getAndIncrement() < 2) {
                throw new IllegalStateException("simulated report failure");
            }
            return RtpTestPackets.receiverReport(1L, new long[0][]);
        });
        scheduler.start(transport, new InetSocketAddress("127.0.0.1", 4));

        Thread.sleep(120);
        scheduler.stop();

        assertTrue(reports.get() >= 1, "scheduler must survive report build failures");
    }
}
