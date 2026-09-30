package com.github.guoyyit.rtpjitterbufferjava.buffer;

import java.util.concurrent.atomic.AtomicInteger;

public class GlobalBufferBudget implements BufferBudget {

    public static final int UNLIMITED = -1;

    private final int maxPackets;
    private final AtomicInteger used = new AtomicInteger();

    public GlobalBufferBudget(int maxPackets) {
        this.maxPackets = maxPackets;
    }

    @Override
    public boolean tryAcquire() {
        if (maxPackets == UNLIMITED) {
            used.incrementAndGet();
            return true;
        }
        while (true) {
            int current = used.get();
            if (current >= maxPackets) {
                return false;
            }
            if (used.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    @Override
    public void release() {
        int current = used.decrementAndGet();
        if (current < 0) {
            used.set(0);
        }
    }

    @Override
    public int available() {
        if (maxPackets == UNLIMITED) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, maxPackets - used.get());
    }

    @Override
    public int used() {
        return used.get();
    }
}
