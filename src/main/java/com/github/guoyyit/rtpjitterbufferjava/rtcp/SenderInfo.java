package com.github.guoyyit.rtpjitterbufferjava.rtcp;

/**
 * SR sender info block (20 bytes, RFC 3550 section 6.4.1).
 */
public record SenderInfo(
        long ntpTimestamp,
        long rtpTimestamp,
        long packetCount,
        long octetCount) {
}
