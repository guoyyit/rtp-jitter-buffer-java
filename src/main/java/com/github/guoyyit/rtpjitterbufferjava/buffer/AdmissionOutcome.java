package com.github.guoyyit.rtpjitterbufferjava.buffer;

/**
 * Result of admitting a packet to a jitter buffer.
 */
public enum AdmissionOutcome {
    ACCEPTED,
    DUPLICATE,
    LATE,
    REORDER_WINDOW_EXCEEDED,
    BUFFER_FULL,
    GLOBAL_LIMIT
}
