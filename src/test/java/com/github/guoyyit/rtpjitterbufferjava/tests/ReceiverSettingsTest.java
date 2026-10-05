package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.config.RtpReceiverProperties;
import com.github.guoyyit.rtpjitterbufferjava.config.RtpReceiverSettings;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSessionConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceiverSettingsTest {

    @Test
    void propertiesDriveRtcpIntervalWindowAndLimits() {
        RtpReceiverProperties p = new RtpReceiverProperties();
        p.setRtcpInterval(Duration.ofMillis(123));
        p.setReorderWindowPackets(99);
        p.setMaxPacketsPerSsrc(7);
        p.setMaxPacketsPerSession(21);
        p.setMaxSsrcs(3);
        p.setClockRate(16000);
        p.setPayloadType(8);

        RtpSessionConfig config = RtpReceiverSettings.toSessionConfig(p, 555, "c@x");
        assertEquals(Duration.ofMillis(123), config.rtcpInterval());
        assertEquals(99, config.jitterBuffer().reorderWindowPackets());
        assertEquals(7, config.jitterBuffer().maxPacketsPerSsrc());
        assertEquals(21, config.maxPacketsPerSession());
        assertEquals(3, config.maxSsrcs());
        assertEquals(16000, config.clockRate());
        assertEquals("c@x", config.cname());
        assertEquals(555, config.localSsrc());
    }

    @Test
    void defaultsRemainBackwardCompatible() {
        RtpSessionConfig config =
                RtpReceiverSettings.toSessionConfig(new RtpReceiverProperties(), 1);
        assertEquals(Duration.ofSeconds(5), config.rtcpInterval());
        assertEquals(64, config.jitterBuffer().reorderWindowPackets());
        assertEquals(512, config.jitterBuffer().maxPacketsPerSsrc());
    }
}
