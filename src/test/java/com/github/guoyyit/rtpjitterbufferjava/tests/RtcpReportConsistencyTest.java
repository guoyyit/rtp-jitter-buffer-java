package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.GlobalBufferBudget;
import com.github.guoyyit.rtpjitterbufferjava.clock.TestClock;
import com.github.guoyyit.rtpjitterbufferjava.codec.PayloadTypeRegistry;
import com.github.guoyyit.rtpjitterbufferjava.config.BufferProperties;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.NtpTime;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpCodec;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacket;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.ReceiverReport;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.ReportBlock;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.SenderReport;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtcpReportConsistencyTest {

    private static final InetSocketAddress PEER = new InetSocketAddress("127.0.0.1", 4000);

    private static RtpSession session(TestClock clock, BufferProperties props) {
        PayloadTypeRegistry registry = new PayloadTypeRegistry(Set.of(0), 8000);
        return new RtpSession("rtcp", 4242L, props, registry,
                new GlobalBufferBudget(props.getGlobalMaxPackets()), clock);
    }

    private static void feed(TestClock clock, RtpSession session, int seq, long timestamp,
                             long ssrc, long arrivalMs) {
        clock.setTime(arrivalMs);
        byte[] packet = RtpTestPackets.rtp(0, seq, timestamp, ssrc, new byte[] {1, 2});
        session.onRtp(packet, 0, packet.length, PEER);
    }

    @Test
    void receiverReportMatchesRealArrivals() {
        TestClock clock = new TestClock();
        BufferProperties props = BufferProperties.defaults();
        props.setMinDelayMs(0);
        props.setInitialDelayMs(0);
        RtpSession session = session(clock, props);

        for (int seq = 0; seq < 10; seq++) {
            feed(clock, session, seq, seq * 160L, 777L, seq * 20L);
        }
        feed(clock, session, 11, 11 * 160L, 777L, 220L);

        byte[] compound = session.buildRtcpReport();
        ReceiverReport rr = (ReceiverReport) new RtcpCodec().parse(compound).get(0);

        assertEquals(4242L, rr.ssrc());
        assertEquals(1, rr.reportBlocks().size());
        ReportBlock block = rr.reportBlocks().get(0);
        assertEquals(777L, block.ssrc());
        assertEquals(11, block.extendedHighestSequence());
        assertEquals(1, block.cumulativeLost());
        assertEquals(0, block.lastSenderReport());
    }

    @Test
    void echoesLsrAndDlsrAfterReceivingSenderReport() {
        TestClock clock = new TestClock();
        RtpSession session = session(clock, BufferProperties.defaults());

        feed(clock, session, 0, 0, 777L, 0);

        long srNtp = NtpTime.fromMillis(1_000_000L);
        byte[] sr = RtpTestPackets.senderReport(777L, srNtp, 0, 1, 2);

        clock.setTime(1_000_500L);
        session.onRtcp(sr, 0, sr.length, PEER);

        clock.setTime(1_001_000L);
        byte[] first = session.buildRtcpReport();
        ReportBlock firstBlock = ((ReceiverReport) new RtcpCodec().parse(first).get(0))
                .reportBlocks().get(0);

        assertEquals(NtpTime.middle32(srNtp), firstBlock.lastSenderReport());
        assertTrue(firstBlock.delaySinceLastSenderReport() > 0,
                "DLSR must reflect elapsed time since the SR arrived");
    }

    @Test
    void ignoresMalformedRtcpAndCountsIt() {
        TestClock clock = new TestClock();
        RtpSession session = session(clock, BufferProperties.defaults());

        byte[] broken = {(byte) 0x80, (byte) 201, 0x00, 0x05, 0, 0, 0, 0};
        session.onRtcp(broken, 0, broken.length, PEER);

        assertEquals(1, session.getCounters().malformedRtcpCount());
    }

    @Test
    void byeRemovesTheSsrcFromReports() {
        TestClock clock = new TestClock();
        RtpSession session = session(clock, BufferProperties.defaults());

        feed(clock, session, 0, 0, 777L, 0);
        byte[] bye = RtpTestPackets.bye(777L, "end");
        session.onRtcp(bye, 0, bye.length, PEER);

        ReceiverReport rr = (ReceiverReport) new RtcpCodec()
                .parse(session.buildRtcpReport()).get(0);
        assertTrue(rr.reportBlocks().isEmpty());
    }
}
