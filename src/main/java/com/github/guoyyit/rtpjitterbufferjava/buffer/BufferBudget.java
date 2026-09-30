package com.github.guoyyit.rtpjitterbufferjava.buffer;

public interface BufferBudget {

    boolean tryAcquire();

    void release();

    int available();

    int used();
}
