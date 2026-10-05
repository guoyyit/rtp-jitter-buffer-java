package com.github.guoyyit.rtpjitterbufferjava.config;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPayloadTypes;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSessionConfig;

/**
 * Translates bound {@code rtp.*} properties into the library's
 * {@link RtpSessionConfig}. Existing property names are unchanged; only the
 * meaning of {@code rtp.reorder-window-packets} is updated (it bounds the
 * backward reorder distance, not forward burst jumps).
 */
public final class RtpReceiverSettings {

    private RtpReceiverSettings() {
    }

    public static RtpSessionConfig toSessionConfig(RtpReceiverProperties properties,
                                                   int localSsrc,
                                                   String cname) {
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(properties.getMinDelay())
                .initialDelay(properties.getInitialDelay())
                .maxDelay(properties.getMaxDelay())
                .reorderWindowPackets(properties.getReorderWindowPackets())
                .maxPacketsPerSsrc(properties.getMaxPacketsPerSsrc())
                .build();
        return RtpSessionConfig.builder()
                .localSsrc(localSsrc)
                .cname(cname)
                .clockRate(properties.getClockRate())
                .payloadTypes(RtpPayloadTypes.of(properties.getPayloadType()))
                .jitterBuffer(jitter)
                .maxSsrcs(properties.getMaxSsrcs())
                .maxPacketsPerSession(properties.getMaxPacketsPerSession())
                .rtcpInterval(properties.getRtcpInterval())
                .build();
    }

    public static RtpSessionConfig toSessionConfig(RtpReceiverProperties properties,
                                                   int localSsrc) {
        return toSessionConfig(properties, localSsrc, "rtp-jitter-buffer");
    }
}
