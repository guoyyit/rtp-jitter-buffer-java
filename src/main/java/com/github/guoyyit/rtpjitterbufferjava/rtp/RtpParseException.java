package com.github.guoyyit.rtpjitterbufferjava.rtp;

/**
 * Thrown when an inbound RTP packet cannot be parsed or fails validation.
 */
public class RtpParseException extends RuntimeException {

    private final RtpRejectReason reason;

    public RtpParseException(RtpRejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RtpRejectReason getReason() {
        return reason;
    }
}
