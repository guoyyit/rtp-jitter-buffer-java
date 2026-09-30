package com.github.guoyyit.rtpjitterbufferjava.rtp;

/**
 * Parsed RTP header extension (RFC 3550 section 5.3.1).
 */
public class RtpHeaderExtension {

    private final int profileType;
    private final int wordCount;
    private final byte[] data;

    public RtpHeaderExtension(int profileType, int wordCount, byte[] data) {
        this.profileType = profileType & 0xFFFF;
        this.wordCount = wordCount;
        this.data = data == null ? new byte[0] : data.clone();
    }

    public int profileType() {
        return profileType;
    }

    /** Length of the extension data in 32-bit words (the on-wire length field). */
    public int wordCount() {
        return wordCount;
    }

    public byte[] data() {
        return data.clone();
    }

    /** Total bytes this extension occupies: the 4-byte prefix plus data words. */
    public int totalLength() {
        return 4 + wordCount * 4;
    }
}
