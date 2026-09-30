package com.github.guoyyit.rtpjitterbufferjava.clock;

public final class TestClock implements Clock {

    private long millis;

    public TestClock() {
    }

    public void setTime(long millis) {
        this.millis = millis;
    }

    public void advance(long millis) {
        this.millis += millis;
    }

    @Override
    public long currentTimeMillis() {
        return millis;
    }

    @Override
    public long nanoTime() {
        return millis * 1_000_000L;
    }
}
