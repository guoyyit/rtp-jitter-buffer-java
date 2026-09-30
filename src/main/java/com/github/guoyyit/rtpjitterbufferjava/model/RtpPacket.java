package com.github.guoyyit.rtpjitterbufferjava.model;

import java.util.Arrays;

public record RtpPacket(
        boolean padding,
        boolean extension,
        int csrcCount,
        boolean marker,
        int payloadType,
        int sequence,
        long timestamp,
        long ssrc,
        long[] csrc,
        RtpExtension headerExtension,
        int payloadOffset,
        int payloadLength,
        byte[] raw
) {

    public byte[] payloadSlice() {
        return Arrays.copyOfRange(raw, payloadOffset, payloadOffset + payloadLength);
    }
}
