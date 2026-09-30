package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.BufferedPacket;
import com.github.guoyyit.rtpjitterbufferjava.buffer.GlobalBufferBudget;
import com.github.guoyyit.rtpjitterbufferjava.clock.TestClock;
import com.github.guoyyit.rtpjitterbufferjava.codec.PayloadTypeRegistry;
import com.github.guoyyit.rtpjitterbufferjava.codec.RejectReason;
import com.github.guoyyit.rtpjitterbufferjava.config.BufferProperties;
import com.github.guoyyit.rtpjitterbufferjava.session.CollisionPolicy;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpSessionTest {

    private static final SocketAddress ALICE = new InetSocketAddress("127.0.0.1", 5002);
    private static final SocketAddress BOB = new InetSocketAddress("127.0.0.1", 5004);

    private static BufferProperties properties() {
        BufferProperties props = BufferProperties.defaults();
        props.setMinDelayMs(0);
        props.setInitialDelayMs(0);
        props.setMaxDelayMs(1000);
        props.setPerSessionMaxPackets(128);
        props.setGlobalMaxPackets(GlobalBufferBudget.UNLIMITED);
        return props;
    }

    private static RtpSession session(TestClock clock, BufferProperties props) {
        PayloadTypeRegistry registry = new PayloadTypeRegistry(
                java.util.Set.of(RtpTestPackets.PT_PCMU), 8000);
        return new RtpSession("s1", 9999L, props, registry,
                new GlobalBufferBudget(props.getGlobalMaxPackets()), clock);
    }

    private static void feed(TestClock clock, RtpSession session, SocketAddress source, int pt,
                             int seq, long timestamp, long ssrc, long arrivalMs) {
        clock.setTime(arrivalMs);
        byte[] packet = RtpTestPackets.rtp(pt, seq, timestamp, ssrc, new byte[] {(byte) seq});
        session.onRtp(packet, 0, packet.length, source);
    }

    @Test
    void countsEveryRejectedPacketByReason() {
        TestClock clock = new TestClock();
        RtpSession session = session(clock, properties());

        byte[] badVersion = RtpTestPackets.rtp(0, RtpTestPackets.PT_PCMU, 1, 1, 1, new byte[] {1});
        session.onRtp(badVersion, 0, badVersion.length, ALICE);
        session.onRtp(new byte[] {0, 0, 0}, 0, 3, ALICE);
        byte[] unknownPt = RtpTestPackets.rtp(RtpTestPackets.PT_DYNAMIC + 20, 1, 1, 1,
                new byte[] {1});
        session.onRtp(unknownPt, 0, unknownPt.length, ALICE);

        assertEquals(1, session.getCounters().rejectedCount(RejectReason.VERSION_MISMATCH));
        assertEquals(1, session.getCounters().rejectedCount(RejectReason.TRUNCATED_HEADER));
        assertEquals(1, session.getCounters().rejectedCount(RejectReason.PAYLOAD_TYPE_UNKNOWN));
        assertEquals(3, session.getCounters().totalRejected());
        assertTrue(session.getReceptionStats().isEmpty());
    }

    @Test
    void multiplexesIndependentSsrcState() {
        TestClock clock = new TestClock();
        RtpSession session = session(clock, properties());

        feed(clock, session, ALICE, 0, 10, 160, 111, 0);
        feed(clock, session, ALICE, 0, 10, 8000, 222, 0);
        feed(clock, session, ALICE, 0, 12, 480, 111, 20);
        feed(clock, session, ALICE, 0, 12, 24000, 222, 20);

        assertEquals(2, session.getReceptionStats().size());
        session.pollPlayable(0);

        var bySsrc = new java.util.HashMap<Long, com.github.guoyyit.rtpjitterbufferjava.stats.ReceptionStats>();
        session.getReceptionStats().forEach(stats -> bySsrc.put(stats.getSsrc(), stats));

        assertEquals(2, bySsrc.get(111L).getReceivedPackets());
        assertEquals(1, bySsrc.get(111L).getCumulativeLost());
        assertEquals(2, bySsrc.get(222L).getReceivedPackets());
        assertEquals(1, bySsrc.get(222L).getCumulativeLost());
    }

    @Test
    void keepsSingleCopyOfDuplicatePacket() {
        TestClock clock = new TestClock();
        RtpSession session = session(clock, properties());

        feed(clock, session, ALICE, 0, 5, 160, 111, 0);
        feed(clock, session, ALICE, 0, 5, 160, 111, 1);

        assertEquals(1, session.getCounters().duplicates());
        assertEquals(1, session.bufferedPackets());
    }

    @Test
    void terminatesSsrcOnAddressCollisionByDefault() {
        TestClock clock = new TestClock();
        RtpSession session = session(clock, properties());

        feed(clock, session, ALICE, 0, 1, 160, 111, 0);
        feed(clock, session, BOB, 0, 2, 320, 111, 1);

        assertEquals(1, session.getCounters().collisions());
        assertEquals(1, session.getCollisionEvents().size());
        assertTrue(session.getCollisionEvents().get(0).reason().contains("transport address"));
        assertTrue(session.getReceptionStats().isEmpty(), "colliding SSRC must be terminated");
    }

    @Test
    void switchesSourceWhenCollisionPolicyIsSwitch() {
        TestClock clock = new TestClock();
        BufferProperties props = properties();
        props.setCollisionPolicy(CollisionPolicy.SWITCH);
        props.setCollisionLimit(Integer.MAX_VALUE);
        RtpSession session = session(clock, props);

        feed(clock, session, ALICE, 0, 1, 160, 111, 0);
        feed(clock, session, BOB, 0, 2, 320, 111, 1);
        feed(clock, session, BOB, 0, 3, 480, 111, 2);

        assertEquals(1, session.getCounters().collisions());
        assertEquals(1, session.getReceptionStats().size());
        assertEquals(3, session.getReceptionStats().iterator().next().getReceivedPackets());
    }

    @Test
    void rejectsPacketWithSessionOwnSsrc() {
        TestClock clock = new TestClock();
        RtpSession session = session(clock, properties());

        feed(clock, session, ALICE, 0, 1, 160, 9999, 0);

        assertEquals(1, session.getCounters().collisions());
        assertTrue(session.getReceptionStats().isEmpty());
    }

    @Test
    void playsPacketsInSequenceAfterReordering() {
        TestClock clock = new TestClock();
        RtpSession session = session(clock, properties());

        feed(clock, session, ALICE, 0, 1, 160, 111, 0);
        feed(clock, session, ALICE, 0, 3, 480, 111, 20);
        feed(clock, session, ALICE, 0, 2, 320, 111, 10);

        clock.setTime(100);
        List<BufferedPacket> playable = session.pollPlayable(0);

        assertEquals(3, playable.size());
        assertEquals(1L, playable.get(0).extendedSequence());
        assertEquals(2L, playable.get(1).extendedSequence());
        assertEquals(3L, playable.get(2).extendedSequence());
    }

    @Test
    void dropsAndCountsLatePacketsAfterPlayout() {
        TestClock clock = new TestClock();
        RtpSession session = session(clock, properties());

        feed(clock, session, ALICE, 0, 10, 1600, 111, 0);
        feed(clock, session, ALICE, 0, 11, 1760, 111, 20);
        clock.setTime(100);
        session.pollPlayable(0);

        feed(clock, session, ALICE, 0, 10, 1600, 111, 120);

        assertEquals(1, session.getCounters().lateDrops());
    }
}
