package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.GlobalBufferBudget;
import com.github.guoyyit.rtpjitterbufferjava.clock.TestClock;
import com.github.guoyyit.rtpjitterbufferjava.codec.PayloadTypeRegistry;
import com.github.guoyyit.rtpjitterbufferjava.config.BufferProperties;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.NtpTime;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RoundTripTimeTest {

    private static final InetSocketAddress PEER = new InetSocketAddress("127.0.0.1", 4000);

    @Test
    void computesRoundTripFromLsrAndDlsr() {
        TestClock clock = new TestClock();
        RtpSession session = new RtpSession("rtt", 1, BufferProperties.defaults(),
                new PayloadTypeRegistry(Set.of(0), 8000),
                new GlobalBufferBudget(GlobalBufferBudget.UNLIMITED), clock);

        clock.setTime(0);
        byte[] firstRtp = RtpTestPackets.rtp(0, 0, 0, 777L, new byte[] {1});
        session.onRtp(firstRtp, 0, firstRtp.length, PEER);

        long srNtp = NtpTime.fromMillis(0L);
        byte[] sr = RtpTestPackets.senderReport(777L, srNtp, 0, 1, 1);
        clock.setTime(100L);
        session.onRtcp(sr, 0, sr.length, PEER);

        clock.setTime(200L);
        session.buildRtcpReport();

        long rttNtp = session.getReceptionStats().iterator().next().getRoundTripTimeNtpUnits();
        long expectedApprox = 100L * 65536L * 65536L / 1000L;
        assertTrue(Math.abs(rttNtp - expectedApprox) < 65536L * 65536L / 50,
                "RTT should approximate 100ms in NTP units, got " + rttNtp);
    }
}
