package com.github.guoyyit.rtpjitterbufferjava.session;

/**
 * Immutable record of a detected SSRC collision or loop.
 */
public record SsrcConflictEvent(
        long ssrc,
        String reason,
        boolean terminated,
        long nanos) {
}
