package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.GlobalBufferBudget;
import com.github.guoyyit.rtpjitterbufferjava.clock.TestClock;
import com.github.guoyyit.rtpjitterbufferjava.codec.PayloadTypeRegistry;
import com.github.guoyyit.rtpjitterbufferjava.config.BufferProperties;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptivePlayoutIntegrationTest {

    private static final InetSocketAddress PEER = new InetSocketAddress("127.0.0.1", 7000);

    @Test
    void absorbsJitterThenConvergesWhenNetworkRecovers() {
        TestClock clock = new TestClock();
        BufferProperties props = BufferProperties.defaults();
        props.setMinDelayMs(20);
        props.setInitialDelayMs(40);
        props.setMaxDelayMs(400);
        props.setJitterSafetyFactor(2.0);
        props.setDelayRecoveryMsPerSecond(100);

        RtpSession session = new RtpSession("adaptive", 1, props,
                new PayloadTypeRegistry(Set.of(0), 8000),
                new GlobalBufferBudget(GlobalBufferBudget.UNLIMITED), clock);

        long calmDelay = feedStream(session, clock, 0, 20, 0, 20L);

        long inflatedDelay = 40L;
        for (int seq = 20; seq < 40; seq++) {
            long spike = (seq % 2 == 0) ? 0L : 120L;
            inflatedDelay = feedOne(session, clock, seq, seq * 20L + spike);
        }

        assertTrue(inflatedDelay > calmDelay,
                "delay must grow during jitter: " + calmDelay + " -> " + inflatedDelay);

        long recoveredDelay = inflatedDelay;
        for (int seq = 40; seq < 240; seq++) {
            recoveredDelay = feedOne(session, clock, seq, seq * 20L);
        }

        assertTrue(recoveredDelay < inflatedDelay,
                "delay must converge down after recovery: " + inflatedDelay
                        + " -> " + recoveredDelay);
        assertTrue(recoveredDelay >= props.getMinDelayMs());
    }

    private static long feedStream(RtpSession session, TestClock clock, int from, int to,
                                   long baseArrival, long spacing) {
        long delay = 0;
        for (int seq = from; seq < to; seq++) {
            delay = feedOne(session, clock, seq, baseArrival + seq * spacing);
        }
        return delay;
    }

    private static long feedOne(RtpSession session, TestClock clock, int seq, long arrival) {
        clock.setTime(arrival);
        byte[] packet = RtpTestPackets.rtp(0, seq, seq * 160L, 555L, new byte[] {1});
        session.onRtp(packet, 0, packet.length, PEER);
        return session.currentDelayMs(555L);
    }
}
