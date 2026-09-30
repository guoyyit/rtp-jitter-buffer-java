package com.github.guoyyit.rtpjitterbufferjava.buffer;

import java.time.Duration;

/**
 * Configuration of one SSRC's adaptive jitter buffer.
 */
public class JitterBufferConfig {

    private final Duration minDelay;
    private final Duration maxDelay;
    private final Duration initialDelay;
    private final int reorderWindowPackets;
    private final double growGain;
    private final double shrinkGain;
    private final double safetyMultiplier;
    private final int maxPacketsPerSsrc;

    private JitterBufferConfig(Builder builder) {
        this.minDelay = builder.minDelay;
        this.maxDelay = builder.maxDelay;
        this.initialDelay = builder.initialDelay;
        this.reorderWindowPackets = builder.reorderWindowPackets;
        this.growGain = builder.growGain;
        this.shrinkGain = builder.shrinkGain;
        this.safetyMultiplier = builder.safetyMultiplier;
        this.maxPacketsPerSsrc = builder.maxPacketsPerSsrc;
        validate();
    }

    public static JitterBufferConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Duration minDelay() {
        return minDelay;
    }

    public Duration maxDelay() {
        return maxDelay;
    }

    public Duration initialDelay() {
        return initialDelay;
    }

    public int reorderWindowPackets() {
        return reorderWindowPackets;
    }

    public double growGain() {
        return growGain;
    }

    public double shrinkGain() {
        return shrinkGain;
    }

    public double safetyMultiplier() {
        return safetyMultiplier;
    }

    public int maxPacketsPerSsrc() {
        return maxPacketsPerSsrc;
    }

    private void validate() {
        if (minDelay.isNegative() || minDelay.compareTo(initialDelay) > 0
                || initialDelay.compareTo(maxDelay) > 0) {
            throw new IllegalArgumentException(
                    "require 0 <= minDelay <= initialDelay <= maxDelay");
        }
        if (reorderWindowPackets < 0 || maxPacketsPerSsrc <= 0) {
            throw new IllegalArgumentException("packet window/limit must be positive");
        }
        if (growGain < 0 || shrinkGain < 0 || safetyMultiplier <= 0) {
            throw new IllegalArgumentException("jitter control gains must be non-negative");
        }
    }

    public static final class Builder {
        private Duration minDelay = Duration.ofMillis(10);
        private Duration maxDelay = Duration.ofMillis(400);
        private Duration initialDelay = Duration.ofMillis(60);
        private int reorderWindowPackets = 64;
        private double growGain = 0.5;
        private double shrinkGain = 0.05;
        private double safetyMultiplier = 1.5;
        private int maxPacketsPerSsrc = 512;

        public Builder minDelay(Duration value) {
            this.minDelay = value;
            return this;
        }

        public Builder maxDelay(Duration value) {
            this.maxDelay = value;
            return this;
        }

        public Builder initialDelay(Duration value) {
            this.initialDelay = value;
            return this;
        }

        public Builder reorderWindowPackets(int value) {
            this.reorderWindowPackets = value;
            return this;
        }

        public Builder growGain(double value) {
            this.growGain = value;
            return this;
        }

        public Builder shrinkGain(double value) {
            this.shrinkGain = value;
            return this;
        }

        public Builder safetyMultiplier(double value) {
            this.safetyMultiplier = value;
            return this;
        }

        public Builder maxPacketsPerSsrc(int value) {
            this.maxPacketsPerSsrc = value;
            return this;
        }

        public JitterBufferConfig build() {
            return new JitterBufferConfig(this);
        }
    }
}
