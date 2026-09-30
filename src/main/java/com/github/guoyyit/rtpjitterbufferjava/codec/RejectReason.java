package com.github.guoyyit.rtpjitterbufferjava.codec;

public enum RejectReason {
    VERSION_MISMATCH,
    TRUNCATED_HEADER,
    INVALID_CSRC_LENGTH,
    INVALID_EXTENSION,
    INVALID_PADDING,
    PAYLOAD_TYPE_UNKNOWN
}
