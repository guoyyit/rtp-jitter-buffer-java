package com.github.guoyyit.rtpjitterbufferjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Spring binding for rtp.* configuration properties.
 */
@ConfigurationProperties(prefix = "rtp")
public class RtpReceiverProperties {

    private int globalMaxBufferedPackets = 16_384;
    private long clockRate = 8000;
    private int payloadType = 0;
    private int maxSsrcs = 32;
    private int maxPacketsPerSession = 4096;
    private int maxPacketsPerSsrc = 512;
    private int reorderWindowPackets = 64;
    private Duration minDelay = Duration.ofMillis(10);
    private Duration maxDelay = Duration.ofMillis(400);
    private Duration initialDelay = Duration.ofMillis(60);
    private Duration rtcpInterval = Duration.ofSeconds(5);

    public int getGlobalMaxBufferedPackets() {
        return globalMaxBufferedPackets;
    }

    public void setGlobalMaxBufferedPackets(int globalMaxBufferedPackets) {
        this.globalMaxBufferedPackets = globalMaxBufferedPackets;
    }

    public long getClockRate() {
        return clockRate;
    }

    public void setClockRate(long clockRate) {
        this.clockRate = clockRate;
    }

    public int getPayloadType() {
        return payloadType;
    }

    public void setPayloadType(int payloadType) {
        this.payloadType = payloadType;
    }

    public int getMaxSsrcs() {
        return maxSsrcs;
    }

    public void setMaxSsrcs(int maxSsrcs) {
        this.maxSsrcs = maxSsrcs;
    }

    public int getMaxPacketsPerSession() {
        return maxPacketsPerSession;
    }

    public void setMaxPacketsPerSession(int maxPacketsPerSession) {
        this.maxPacketsPerSession = maxPacketsPerSession;
    }

    public int getMaxPacketsPerSsrc() {
        return maxPacketsPerSsrc;
    }

    public void setMaxPacketsPerSsrc(int maxPacketsPerSsrc) {
        this.maxPacketsPerSsrc = maxPacketsPerSsrc;
    }

    public int getReorderWindowPackets() {
        return reorderWindowPackets;
    }

    public void setReorderWindowPackets(int reorderWindowPackets) {
        this.reorderWindowPackets = reorderWindowPackets;
    }

    public Duration getMinDelay() {
        return minDelay;
    }

    public void setMinDelay(Duration minDelay) {
        this.minDelay = minDelay;
    }

    public Duration getMaxDelay() {
        return maxDelay;
    }

    public void setMaxDelay(Duration maxDelay) {
        this.maxDelay = maxDelay;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
    }

    public Duration getRtcpInterval() {
        return rtcpInterval;
    }

    public void setRtcpInterval(Duration rtcpInterval) {
        this.rtcpInterval = rtcpInterval;
    }
}
