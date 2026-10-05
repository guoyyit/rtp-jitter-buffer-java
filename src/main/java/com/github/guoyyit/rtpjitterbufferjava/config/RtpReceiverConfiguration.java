package com.github.guoyyit.rtpjitterbufferjava.config;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.engine.RtpReceiver;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPayloadTypes;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSessionConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@code rtp.*} properties into {@link RtpReceiverProperties} and
 * exposes the receiver/session beans built from them.
 */
@Configuration
@EnableConfigurationProperties(RtpReceiverProperties.class)
public class RtpReceiverConfiguration {

    @Bean(destroyMethod = "close")
    public RtpReceiver rtpReceiver(RtpReceiverProperties properties) {
        return new RtpReceiver(properties.getGlobalMaxBufferedPackets());
    }

    /**
     * Session template honouring every {@code rtp.*} property, including
     * {@code rtp.rtcp-interval} for the compound report cadence.
     */
    @Bean
    public RtpSessionConfig rtpSessionConfig(RtpReceiverProperties properties) {
        JitterBufferConfig jitter = JitterBufferConfig.builder()
                .minDelay(properties.getMinDelay())
                .initialDelay(properties.getInitialDelay())
                .maxDelay(properties.getMaxDelay())
                .reorderWindowPackets(properties.getReorderWindowPackets())
                .maxPacketsPerSsrc(properties.getMaxPacketsPerSsrc())
                .build();
        return RtpSessionConfig.builder()
                .clockRate(properties.getClockRate())
                .payloadTypes(RtpPayloadTypes.of(properties.getPayloadType()))
                .jitterBuffer(jitter)
                .maxSsrcs(properties.getMaxSsrcs())
                .maxPacketsPerSession(properties.getMaxPacketsPerSession())
                .rtcpInterval(properties.getRtcpInterval())
                .build();
    }
}
