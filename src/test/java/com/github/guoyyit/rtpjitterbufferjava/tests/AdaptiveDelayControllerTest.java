package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.buffer.AdaptiveDelayController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveDelayControllerTest {

    @Test
    void startsAtInitialDelay() {
        AdaptiveDelayController controller =
                new AdaptiveDelayController(80, 20, 400, 1.5, 100);

        assertEquals(80, controller.currentDelayMs());
    }

    @Test
    void increasesImmediatelyWhenJitterGrows() {
        AdaptiveDelayController controller =
                new AdaptiveDelayController(80, 20, 400, 1.5, 100);

        long first = controller.nextDelay(0, 100);
        long second = controller.nextDelay(100, 200);

        assertTrue(second >= first);
        assertEquals(300, second);
    }

    @Test
    void recoversGraduallyWhenNetworkImproves() {
        AdaptiveDelayController controller =
                new AdaptiveDelayController(80, 20, 400, 1.5, 100);

        controller.nextDelay(0, 200);
        long inflated = controller.currentDelayMs();

        long afterOneSecond = controller.nextDelay(1000, 10);

        assertTrue(afterOneSecond < inflated, "delay should converge down: " + inflated
                + " -> " + afterOneSecond);
        assertTrue(afterOneSecond >= 20, "delay must stay above the configured minimum");
    }

    @Test
    void neverLeavesConfiguredBounds() {
        AdaptiveDelayController controller =
                new AdaptiveDelayController(80, 20, 300, 1.0, 100);

        assertEquals(300, controller.nextDelay(0, 100_000));

        long low = controller.nextDelay(10_000, 0);
        assertEquals(20, low);
    }

    @Test
    void staysAtTargetWhenNetworkIsStable() {
        AdaptiveDelayController controller =
                new AdaptiveDelayController(60, 20, 400, 1.0, 100);

        controller.nextDelay(0, 60);
        long stable = controller.nextDelay(1000, 60);

        assertEquals(60, stable);
    }
}
