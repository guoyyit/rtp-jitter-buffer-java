package com.github.guoyyit.rtpjitterbufferjava.codec;

public class RtpFormatException extends RuntimeException {

    private final RejectReason reason;

    public RtpFormatException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
