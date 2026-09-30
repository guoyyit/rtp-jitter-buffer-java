package com.github.guoyyit.rtpjitterbufferjava.clock;

/**
 * Injectable time source so that buffering and jitter calculations can be
 * driven deterministically from tests.
 */
public interface Clock {

    long currentTimeMillis();

    long nanoTime();
}
