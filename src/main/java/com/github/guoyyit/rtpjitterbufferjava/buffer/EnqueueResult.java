package com.github.guoyyit.rtpjitterbufferjava.buffer;

public enum EnqueueResult {
    ACCEPTED,
    DUPLICATE,
    LATE_DROPPED,
    SESSION_FULL,
    GLOBAL_FULL
}
