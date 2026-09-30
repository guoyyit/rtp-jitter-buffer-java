package com.github.guoyyit.rtpjitterbufferjava.buffer;

/**
 * RFC 3550 Appendix A.8 arrival jitter estimator.
 */
public class JitterEstimator {

    private long jitterNanos;
    private boolean primed;

    public void update(long arrivalDeltaNanos, long rtpDeltaNanos) {
        long deviation = Math.abs(arrivalDeltaNanos - rtpDeltaNanos);
        if (!primed) {
            primed = true;
            jitterNanos = deviation;
            return;
        }
        jitterNanos += (deviation - jitterNanos) / 16;
    }

    public long jitterNanos() {
        return jitterNanos;
    }
}
