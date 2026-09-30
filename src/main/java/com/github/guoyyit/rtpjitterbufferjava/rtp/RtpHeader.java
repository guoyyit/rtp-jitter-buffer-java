package com.github.guoyyit.rtpjitterbufferjava.rtp;

import java.util.List;

/**
 * Immutable parsed RTP fixed header plus optional extension and CSRC list.
 */
public class RtpHeader {

    private final int version;
    private final boolean padding;
    private final boolean extension;
    private final int csrcCount;
    private final boolean marker;
    private final int payloadType;
    private final int sequenceNumber;
    private final long timestamp;
    private final long ssrc;
    private final List<Long> csrcs;
    private final RtpHeaderExtension headerExtension;
    private final int headerLength;

    public RtpHeader(int version, boolean padding, boolean extension, int csrcCount,
                     boolean marker, int payloadType, int sequenceNumber, long timestamp,
                     long ssrc, List<Long> csrcs, RtpHeaderExtension headerExtension,
                     int headerLength) {
        this.version = version;
        this.padding = padding;
        this.extension = extension;
        this.csrcCount = csrcCount;
        this.marker = marker;
        this.payloadType = payloadType;
        this.sequenceNumber = sequenceNumber & 0xFFFF;
        this.timestamp = timestamp & 0xFFFFFFFFL;
        this.ssrc = ssrc & 0xFFFFFFFFL;
        this.csrcs = List.copyOf(csrcs);
        this.headerExtension = headerExtension;
        this.headerLength = headerLength;
    }

    public int version() {
        return version;
    }

    public boolean padding() {
        return padding;
    }

    public boolean extension() {
        return extension;
    }

    public int csrcCount() {
        return csrcCount;
    }

    public boolean marker() {
        return marker;
    }

    public int payloadType() {
        return payloadType;
    }

    public int sequenceNumber() {
        return sequenceNumber;
    }

    public long timestamp() {
        return timestamp;
    }

    public long ssrc() {
        return ssrc;
    }

    public List<Long> csrcs() {
        return csrcs;
    }

    public RtpHeaderExtension headerExtension() {
        return headerExtension;
    }

    /** Header size in bytes: fixed 12 + CSRCs + extension. */
    public int headerLength() {
        return headerLength;
    }
}
