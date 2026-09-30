package com.github.guoyyit.rtpjitterbufferjava.clock;

public final class SystemClock implements Clock {

    public static final SystemClock INSTANCE = new SystemClock();

    private SystemClock() {
    }

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}
