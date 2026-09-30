package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.GlobalBufferBudget;
import com.github.guoyyit.rtpjitterbufferjava.clock.TestClock;
import com.github.guoyyit.rtpjitterbufferjava.codec.PayloadTypeRegistry;
import com.github.guoyyit.rtpjitterbufferjava.config.BufferProperties;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSessionManager;
import com.github.guoyyit.rtpjitterbufferjava.transport.InMemoryTransport;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiSessionIsolationTest {

    private static final SocketAddress PEER_A = new InetSocketAddress("127.0.0.1", 6002);
    private static final SocketAddress PEER_B = new InetSocketAddress("127.0.0.1", 6004);

    private static BufferProperties properties() {
        BufferProperties props = BufferProperties.defaults();
        props.setMinDelayMs(0);
        props.setInitialDelayMs(0);
        props.setPerSessionMaxPackets(128);
        props.setGlobalMaxPackets(4);
        return props;
    }

    private static void feed(RtpSessionManager manager, String sessionId, TestClock clock,
                             SocketAddress peer, int seq, long ssrc) {
        clock.setTime(seq * 10L);
        byte[] packet = RtpTestPackets.rtp(0, seq, seq * 160L, ssrc, new byte[] {1});
        manager.routeRtp(sessionId, packet, 0, packet.length, peer);
    }

    @Test
    void parseFailureInOneSessionDoesNotAffectOthers() {
        TestClock clock = new TestClock();
        GlobalBufferBudget budget = new GlobalBufferBudget(GlobalBufferBudget.UNLIMITED);
        RtpSessionManager manager = new RtpSessionManager(properties(),
                new PayloadTypeRegistry(Set.of(0), 8000), budget, clock);

        RtpSession a = manager.createSession("a", 1000L);
        RtpSession b = manager.createSession("b", 2000L);

        byte[] good = RtpTestPackets.rtp(0, 1, 160, 111, new byte[] {1});
        byte[] badVersion = RtpTestPackets.rtp(0, 0, 1, 160, 111, new byte[] {1});

        manager.routeRtp("a", badVersion, 0, badVersion.length, PEER_A);
        manager.routeRtp("b", good, 0, good.length, PEER_B);

        assertEquals(1, a.getCounters().totalRejected());
        assertEquals(0, b.getCounters().totalRejected());
        assertEquals(1, b.getReceptionStats().size());
        assertTrue(a.getReceptionStats().isEmpty());
        assertNotEquals(a, b);
    }

    @Test
    void globalBudgetIsSharedAndBoundedAcrossSessions() {
        TestClock clock = new TestClock();
        GlobalBufferBudget budget = new GlobalBufferBudget(4);
        RtpSessionManager manager = new RtpSessionManager(properties(),
                new PayloadTypeRegistry(Set.of(0), 8000), budget, clock);

        manager.createSession("a", 1000L);
        manager.createSession("b", 2000L);

        for (int seq = 0; seq < 6; seq++) {
            feed(manager, "a", clock, PEER_A, seq, 111L);
            feed(manager, "b", clock, PEER_B, seq, 222L);
        }

        assertEquals(4, budget.used(), "global buffered packets must stay bounded");
        assertEquals(4, manager.totalBufferedPackets());

        long globalDrops = manager.getSessions().stream()
                .mapToLong(session -> session.getCounters().globalBudgetDrops())
                .sum();
        assertTrue(globalDrops > 0, "slow consumers must release pressure via counted drops");
    }

    @Test
    void inMemoryTransportDeliversToBoundSessionOnly() throws Exception {
        TestClock clock = new TestClock();
        GlobalBufferBudget budget = new GlobalBufferBudget(GlobalBufferBudget.UNLIMITED);
        RtpSessionManager manager = new RtpSessionManager(properties(),
                new PayloadTypeRegistry(Set.of(0), 8000), budget, clock);

        InMemoryTransport ta = new InMemoryTransport("a");
        InMemoryTransport tb = new InMemoryTransport("b");
        InMemoryTransport.pair(ta, tb, PEER_A, PEER_B);

        manager.createSession("a", 1000L);
        manager.createSession("b", 2000L);
        manager.bindTransport("a", ta);
        manager.bindTransport("b", tb);

        byte[] packet = RtpTestPackets.rtp(0, 7, 160, 111, new byte[] {1});
        tb.sendRtp(packet, PEER_A);

        assertEquals(1, manager.getSession("a").bufferedPackets());
        assertEquals(0, manager.getSession("b").bufferedPackets());

        manager.closeAll();
    }
}
