package com.github.guoyyit.rtpjitterbufferjava.engine;

/**
 * Shared cap over packets buffered across every session of an RtpReceiver.
 * A session consults it before admitting a packet so a slow consumer in one
 * session cannot exhaust memory for the others.
 */
public interface GlobalBufferLimiter {

    /** Reserves one buffered slot; returns false when the global cap is hit. */
    boolean tryAcquire();

    /** Releases one slot when a packet is played out or dropped. */
    void release();

    /** Slots currently held across all sessions. */
    int currentUsage();

    /** No-op limiter used when sessions run without an RtpReceiver. */
    GlobalBufferLimiter UNLIMITED = new GlobalBufferLimiter() {
        @Override
        public boolean tryAcquire() {
            return true;
        }

        @Override
        public void release() {
        }

        @Override
        public int currentUsage() {
            return 0;
        }
    };
}
