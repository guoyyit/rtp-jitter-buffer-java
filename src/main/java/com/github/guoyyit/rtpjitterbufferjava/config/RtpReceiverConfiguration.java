package com.github.guoyyit.rtpjitterbufferjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@code rtp.*} properties into {@link RtpReceiverProperties}.
 */
@Configuration
@EnableConfigurationProperties(RtpReceiverProperties.class)
public class RtpReceiverConfiguration {
}
