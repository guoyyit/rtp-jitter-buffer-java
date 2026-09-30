package com.github.guoyyit.rtpjitterbufferjava.session;

import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPayloadTypes;

import java.time.Duration;

/**
 * Configuration of one RTP/RTCP session.
 */
public class RtpSessionConfig {

    private final int localSsrc;
    private final long clockRate;
    private final RtpPayloadTypes payloadTypes;
    private final JitterBufferConfig jitterBuffer;
    private final SsrcConflictPolicy conflictPolicy;
    private final int maxSsrcs;
    private final int maxPacketsPerSession;
    private final Duration ssrcIdleTimeout;
    private final Duration rtcpInterval;
    private final String cname;

    private RtpSessionConfig(Builder builder) {
        this.localSsrc = builder.localSsrc;
        this.clockRate = builder.clockRate;
        this.payloadTypes = builder.payloadTypes;
        this.jitterBuffer = builder.jitterBuffer;
        this.conflictPolicy = builder.conflictPolicy;
        this.maxSsrcs = builder.maxSsrcs;
        this.maxPacketsPerSession = builder.maxPacketsPerSession;
        this.ssrcIdleTimeout = builder.ssrcIdleTimeout;
        this.rtcpInterval = builder.rtcpInterval;
        this.cname = builder.cname;
    }

    public static RtpSessionConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int localSsrc() {
        return localSsrc;
    }

    public long clockRate() {
        return clockRate;
    }

    public RtpPayloadTypes payloadTypes() {
        return payloadTypes;
    }

    public JitterBufferConfig jitterBuffer() {
        return jitterBuffer;
    }

    public SsrcConflictPolicy conflictPolicy() {
        return conflictPolicy;
    }

    public int maxSsrcs() {
        return maxSsrcs;
    }

    public int maxPacketsPerSession() {
        return maxPacketsPerSession;
    }

    public long ssrcIdleNanos() {
        return ssrcIdleTimeout.toNanos();
    }

    public Duration rtcpInterval() {
        return rtcpInterval;
    }

    public String cname() {
        return cname;
    }

    public static final class Builder {
        private int localSsrc = 0;
        private long clockRate = 8000;
        private RtpPayloadTypes payloadTypes = RtpPayloadTypes.all();
        private JitterBufferConfig jitterBuffer = JitterBufferConfig.defaults();
        private SsrcConflictPolicy conflictPolicy = SsrcConflictPolicy.SWITCH;
        private int maxSsrcs = 32;
        private int maxPacketsPerSession = 4096;
        private Duration ssrcIdleTimeout = Duration.ofSeconds(30);
        private Duration rtcpInterval = Duration.ofSeconds(5);
        private String cname = "rtp-jitter-buffer";

        public Builder localSsrc(int localSsrc) {
            this.localSsrc = localSsrc;
            return this;
        }

        public Builder clockRate(long clockRate) {
            this.clockRate = clockRate;
            return this;
        }

        public Builder payloadTypes(RtpPayloadTypes payloadTypes) {
            this.payloadTypes = payloadTypes;
            return this;
        }

        public Builder jitterBuffer(JitterBufferConfig jitterBuffer) {
            this.jitterBuffer = jitterBuffer;
            return this;
        }

        public Builder conflictPolicy(SsrcConflictPolicy conflictPolicy) {
            this.conflictPolicy = conflictPolicy;
            return this;
        }

        public Builder maxSsrcs(int maxSsrcs) {
            this.maxSsrcs = maxSsrcs;
            return this;
        }

        public Builder maxPacketsPerSession(int maxPacketsPerSession) {
            this.maxPacketsPerSession = maxPacketsPerSession;
            return this;
        }

        public Builder ssrcIdleTimeout(Duration ssrcIdleTimeout) {
            this.ssrcIdleTimeout = ssrcIdleTimeout;
            return this;
        }

        public Builder rtcpInterval(Duration rtcpInterval) {
            this.rtcpInterval = rtcpInterval;
            return this;
        }

        public Builder cname(String cname) {
            this.cname = cname;
            return this;
        }

        public RtpSessionConfig build() {
            if (clockRate <= 0) {
                throw new IllegalArgumentException("clockRate must be positive");
            }
            if (maxSsrcs <= 0 || maxPacketsPerSession <= 0) {
                throw new IllegalArgumentException("session limits must be positive");
            }
            return new RtpSessionConfig(this);
        }
    }
}
