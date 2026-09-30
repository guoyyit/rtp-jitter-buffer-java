package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.engine.RtpReceiver;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPayloadTypes;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSessionConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.github.guoyyit.rtpjitterbufferjava.tests.RtpPacketParserTest.rtpPacket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpReceiverConcurrencyTest {

    private static RtpSessionConfig config() {
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(Duration.ofSeconds(5))
                .initialDelay(Duration.ofSeconds(10))
                .maxDelay(Duration.ofSeconds(30))
                .maxPacketsPerSsrc(100_000)
                .reorderWindowPackets(100_000)
                .build();
        return RtpSessionConfig.builder()
                .localSsrc(Integer.MAX_VALUE)
                .payloadTypes(RtpPayloadTypes.all())
                .jitterBuffer(jitter)
                .maxPacketsPerSession(1_000_000)
                .maxSsrcs(8)
                .build();
    }

    @Test
    void isolatesSessionsAndCountsMalformedIndependently() throws Exception {
        RtpReceiver receiver = new RtpReceiver(100_000);
        RtpSession a = receiver.createSession("a", config());
        RtpSession b = receiver.createSession("b", config());

        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        int seq = base + i;
                        byte[] raw = rtpPacket(0, seq & 0xFFFF, seq * 80L, 111, false,
                                new byte[]{(byte) seq});
                        a.receive(raw, raw.length, seq * 20_000_000L);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        // Session B receives only malformed packets; A must be unaffected.
        byte[] bad = rtpPacket(0, 0, 0, 1, false, new byte[1]);
        bad[0] = 0x40;
        for (int i = 0; i < 10; i++) {
            b.receive(bad, bad.length, i);
        }

        assertEquals(threads * perThread,
                a.ssrcState(111).orElseThrow().counters().receivedPackets());
        assertEquals(10, b.counters().rejectedPackets());
        assertEquals(0, a.counters().rejectedPackets());
        assertTrue(a.totalBufferedPackets() > 0);
        receiver.close();
    }

    @Test
    void enforcesGlobalBufferCap() {
        RtpReceiver receiver = new RtpReceiver(5);
        RtpSession session = receiver.createSession("s", config());
        int accepted = 0;
        int dropped = 0;
        for (int seq = 0; seq < 50; seq++) {
            byte[] raw = rtpPacket(0, seq, seq * 80L, 111, false, new byte[]{(byte) seq});
            session.receive(raw, raw.length, seq * 1_000_000L);
        }
        accepted = session.totalBufferedPackets();
        dropped = (int) session.aggregateOverloadDroppedPackets();
        assertEquals(5, accepted);
        assertTrue(dropped >= 40, "expected overload drops, got " + dropped);
        assertEquals(5, receiver.totalBufferedPackets());
        receiver.close();
        assertEquals(0, receiver.totalBufferedPackets());
    }
}
