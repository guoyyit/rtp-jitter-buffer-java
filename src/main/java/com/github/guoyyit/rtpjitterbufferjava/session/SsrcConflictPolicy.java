package com.github.guoyyit.rtpjitterbufferjava.session;

/**
 * How a session reacts to an SSRC collision (RFC 3550 sections 8.2/8.3).
 */
public enum SsrcConflictPolicy {
    /** Retire the colliding local SSRC, as required on the wire. */
    SWITCH,
    /** Stop the stream after the mandated collision attempts. */
    TERMINATE
}
