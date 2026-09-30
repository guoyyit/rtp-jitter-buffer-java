package com.github.guoyyit.rtpjitterbufferjava.config;

import com.github.guoyyit.rtpjitterbufferjava.buffer.DropPolicy;
import com.github.guoyyit.rtpjitterbufferjava.session.CollisionPolicy;

import java.util.Set;

public class BufferProperties {

    private int maxReorderWindow = 64;
    private long minDelayMs = 20L;
    private long maxDelayMs = 500L;
    private long initialDelayMs = 80L;
    private double jitterSafetyFactor = 1.5;
    private long delayRecoveryMsPerSecond = 100L;
    private int perSessionMaxPackets = 256;
    private int globalMaxPackets = 4096;
    private DropPolicy dropPolicy = DropPolicy.DROP_NEWEST;
    private long rtcpIntervalMs = 5_000L;
    private CollisionPolicy collisionPolicy = CollisionPolicy.TERMINATE;
    private int collisionLimit = 1;
    private Set<Integer> knownPayloadTypes = Set.of();
    private int dynamicPayloadClockRate = 90_000;

    public BufferProperties() {
    }

    public static BufferProperties defaults() {
        return new BufferProperties();
    }

    public int getMaxReorderWindow() {
        return maxReorderWindow;
    }

    public void setMaxReorderWindow(int maxReorderWindow) {
        this.maxReorderWindow = maxReorderWindow;
    }

    public long getMinDelayMs() {
        return minDelayMs;
    }

    public void setMinDelayMs(long minDelayMs) {
        this.minDelayMs = minDelayMs;
    }

    public long getMaxDelayMs() {
        return maxDelayMs;
    }

    public void setMaxDelayMs(long maxDelayMs) {
        this.maxDelayMs = maxDelayMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public double getJitterSafetyFactor() {
        return jitterSafetyFactor;
    }

    public void setJitterSafetyFactor(double jitterSafetyFactor) {
        this.jitterSafetyFactor = jitterSafetyFactor;
    }

    public long getDelayRecoveryMsPerSecond() {
        return delayRecoveryMsPerSecond;
    }

    public void setDelayRecoveryMsPerSecond(long delayRecoveryMsPerSecond) {
        this.delayRecoveryMsPerSecond = delayRecoveryMsPerSecond;
    }

    public int getPerSessionMaxPackets() {
        return perSessionMaxPackets;
    }

    public void setPerSessionMaxPackets(int perSessionMaxPackets) {
        this.perSessionMaxPackets = perSessionMaxPackets;
    }

    public int getGlobalMaxPackets() {
        return globalMaxPackets;
    }

    public void setGlobalMaxPackets(int globalMaxPackets) {
        this.globalMaxPackets = globalMaxPackets;
    }

    public DropPolicy getDropPolicy() {
        return dropPolicy;
    }

    public void setDropPolicy(DropPolicy dropPolicy) {
        this.dropPolicy = dropPolicy;
    }

    public long getRtcpIntervalMs() {
        return rtcpIntervalMs;
    }

    public void setRtcpIntervalMs(long rtcpIntervalMs) {
        this.rtcpIntervalMs = rtcpIntervalMs;
    }

    public CollisionPolicy getCollisionPolicy() {
        return collisionPolicy;
    }

    public void setCollisionPolicy(CollisionPolicy collisionPolicy) {
        this.collisionPolicy = collisionPolicy;
    }

    public int getCollisionLimit() {
        return collisionLimit;
    }

    public void setCollisionLimit(int collisionLimit) {
        this.collisionLimit = collisionLimit;
    }

    public Set<Integer> getKnownPayloadTypes() {
        return knownPayloadTypes;
    }

    public void setKnownPayloadTypes(Set<Integer> knownPayloadTypes) {
        this.knownPayloadTypes = Set.copyOf(knownPayloadTypes);
    }

    public int getDynamicPayloadClockRate() {
        return dynamicPayloadClockRate;
    }

    public void setDynamicPayloadClockRate(int dynamicPayloadClockRate) {
        this.dynamicPayloadClockRate = dynamicPayloadClockRate;
    }
}
