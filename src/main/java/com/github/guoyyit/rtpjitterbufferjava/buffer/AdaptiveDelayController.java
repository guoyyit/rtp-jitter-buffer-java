package com.github.guoyyit.rtpjitterbufferjava.buffer;

public class AdaptiveDelayController {

    private final long minDelayMs;
    private final long maxDelayMs;
    private final double safetyFactor;
    private final long recoveryMsPerSecond;

    private long currentDelayMs;
    private long lastUpdateMillis = Long.MIN_VALUE;

    public AdaptiveDelayController(long initialDelayMs, long minDelayMs, long maxDelayMs,
                                   double safetyFactor, long recoveryMsPerSecond) {
        if (minDelayMs < 0 || maxDelayMs < minDelayMs) {
            throw new IllegalArgumentException("require 0 <= minDelayMs <= maxDelayMs");
        }
        if (safetyFactor <= 0) {
            throw new IllegalArgumentException("safetyFactor must be positive");
        }
        if (recoveryMsPerSecond < 0) {
            throw new IllegalArgumentException("recoveryMsPerSecond must not be negative");
        }
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.safetyFactor = safetyFactor;
        this.recoveryMsPerSecond = recoveryMsPerSecond;
        this.currentDelayMs = clamp(initialDelayMs);
    }

    public long nextDelay(long nowMillis, long estimatedJitterMs) {
        long target = clamp(Math.round(estimatedJitterMs * safetyFactor));

        if (lastUpdateMillis == Long.MIN_VALUE) {
            currentDelayMs = Math.max(currentDelayMs, target);
            lastUpdateMillis = nowMillis;
            return currentDelayMs;
        }

        long elapsed = nowMillis - lastUpdateMillis;
        if (elapsed > 0) {
            if (target > currentDelayMs) {
                currentDelayMs = target;
            } else if (target < currentDelayMs && recoveryMsPerSecond > 0) {
                long reduction = recoveryMsPerSecond * elapsed / 1000;
                currentDelayMs = Math.max(target, currentDelayMs - reduction);
            }
        }
        lastUpdateMillis = nowMillis;
        return currentDelayMs;
    }

    public long currentDelayMs() {
        return currentDelayMs;
    }

    private long clamp(long value) {
        return Math.max(minDelayMs, Math.min(maxDelayMs, value));
    }
}
