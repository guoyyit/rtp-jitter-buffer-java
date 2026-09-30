package com.github.guoyyit.rtpjitterbufferjava.buffer;

import java.time.Duration;

/**
 * Bounded adaptive playout delay driven by the RFC 3550 jitter estimate.
 */
public class AdaptivePlayoutController {

    private final long minNanos;
    private final long maxNanos;
    private final double growGain;
    private final double shrinkGain;
    private final double safetyMultiplier;
    private long currentNanos;

    public AdaptivePlayoutController(JitterBufferConfig config) {
        this.minNanos = config.minDelay().toNanos();
        this.maxNanos = config.maxDelay().toNanos();
        this.currentNanos = config.initialDelay().toNanos();
        this.growGain = config.growGain();
        this.shrinkGain = config.shrinkGain();
        this.safetyMultiplier = config.safetyMultiplier();
    }

    public void onJitterUpdated(long jitterNanos) {
        long target = (long) (jitterNanos * safetyMultiplier);
        target = Math.max(minNanos, Math.min(maxNanos, target));
        if (target > currentNanos) {
            currentNanos += (long) ((target - currentNanos) * growGain);
            if (currentNanos > target || currentNanos < 0) {
                currentNanos = target;
            }
        } else if (target < currentNanos) {
            currentNanos -= (long) ((currentNanos - target) * shrinkGain);
            if (currentNanos < target) {
                currentNanos = target;
            }
        }
        currentNanos = Math.max(minNanos, Math.min(maxNanos, currentNanos));
    }

    public Duration currentDelay() {
        return Duration.ofNanos(currentNanos);
    }

    public long currentDelayNanos() {
        return currentNanos;
    }
}
