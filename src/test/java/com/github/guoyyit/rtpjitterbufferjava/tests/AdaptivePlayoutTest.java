package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.AdaptivePlayoutController;
import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBufferConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptivePlayoutTest {

    private static AdaptivePlayoutController controller() {
        JitterBufferConfig config = JitterBufferConfig.builder()
                .minDelay(Duration.ofMillis(10))
                .initialDelay(Duration.ofMillis(20))
                .maxDelay(Duration.ofMillis(300))
                .growGain(0.9)
                .shrinkGain(0.02)
                .safetyMultiplier(1.0)
                .build();
        return new AdaptivePlayoutController(config);
    }

    @Test
    void growsQuicklyWhenJitterSpikesThenConvergesSlowly() {
        AdaptivePlayoutController controller = controller();
        long start = controller.currentDelayNanos();

        for (int i = 0; i < 50; i++) {
            controller.onJitterUpdated(Duration.ofMillis(200).toNanos());
        }
        long afterSpike = controller.currentDelayNanos();
        assertTrue(afterSpike > start, "delay must grow under sustained high jitter");
        assertTrue(afterSpike <= Duration.ofMillis(300).toNanos(), "delay must stay bounded");

        long previous = afterSpike;
        for (int i = 0; i < 2000; i++) {
            controller.onJitterUpdated(Duration.ofMillis(5).toNanos());
        }
        long afterRecovery = controller.currentDelayNanos();
        assertTrue(afterRecovery < previous, "delay must converge down after recovery");
        assertTrue(afterRecovery >= Duration.ofMillis(10).toNanos(),
                "delay must not drop below minimum");
    }

    @Test
    void neverExposesNegativeOrOutOfBoundsDelay() {
        AdaptivePlayoutController controller = controller();
        for (long jitter = 0; jitter < 500; jitter++) {
            controller.onJitterUpdated(Duration.ofMillis(jitter % 2 == 0 ? 0 : 400).toNanos());
            long delay = controller.currentDelayNanos();
            assertTrue(delay >= Duration.ofMillis(10).toNanos());
            assertTrue(delay <= Duration.ofMillis(300).toNanos());
        }
    }
}
