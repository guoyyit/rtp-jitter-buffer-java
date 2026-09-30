package com.github.guoyyit.rtpjitterbufferjava.rtp;

/**
 * Reasons an inbound RTP packet can be rejected.
 */
public enum RtpRejectReason {
    VERSION_MISMATCH,
    TRUNCATED_PACKET,
    INVALID_EXTENSION,
    INVALID_PADDING,
    UNKNOWN_PAYLOAD_TYPE,
    CSRC_OUT_OF_RANGE
}
