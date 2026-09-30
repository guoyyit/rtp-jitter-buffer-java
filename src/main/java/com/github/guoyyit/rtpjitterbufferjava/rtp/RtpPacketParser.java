package com.github.guoyyit.rtpjitterbufferjava.rtp;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses and validates raw UDP datagrams into {@link RtpPacket} instances.
 */
public class RtpPacketParser {

    private static final int MIN_HEADER_LENGTH = 12;

    private final RtpPayloadTypes payloadTypes;

    public RtpPacketParser(RtpPayloadTypes payloadTypes) {
        this.payloadTypes = payloadTypes;
    }

    public RtpPacket parse(byte[] raw, int length, long arrivalNanos) {
        if (raw == null || length < MIN_HEADER_LENGTH || raw.length < length) {
            throw new RtpParseException(RtpRejectReason.TRUNCATED_PACKET,
                    "packet shorter than fixed RTP header");
        }

        int b0 = raw[0] & 0xFF;
        int version = (b0 >> 6) & 0x03;
        if (version != 2) {
            throw new RtpParseException(RtpRejectReason.VERSION_MISMATCH,
                    "unsupported RTP version: " + version);
        }

        boolean padding = (b0 & 0x20) != 0;
        boolean extension = (b0 & 0x10) != 0;
        int csrcCount = b0 & 0x0F;

        int b1 = raw[1] & 0xFF;
        boolean marker = (b1 & 0x80) != 0;
        int payloadType = b1 & 0x7F;

        int sequenceNumber = ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
        long timestamp = readUnsignedInt(raw, 4);
        long ssrc = readUnsignedInt(raw, 8);

        int offset = MIN_HEADER_LENGTH + csrcCount * 4;
        if (offset > length) {
            throw new RtpParseException(RtpRejectReason.TRUNCATED_PACKET,
                    "CSRC list runs past end of packet");
        }
        List<Long> csrcs = new ArrayList<>(csrcCount);
        for (int i = 0; i < csrcCount; i++) {
            csrcs.add(readUnsignedInt(raw, MIN_HEADER_LENGTH + i * 4));
        }

        RtpHeaderExtension headerExtension = null;
        if (extension) {
            if (offset + 4 > length) {
                throw new RtpParseException(RtpRejectReason.INVALID_EXTENSION,
                        "truncated RTP extension header");
            }
            int profile = ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF);
            int words = ((raw[offset + 2] & 0xFF) << 8) | (raw[offset + 3] & 0xFF);
            int extDataLength = words * 4;
            if (offset + 4 + extDataLength > length) {
                throw new RtpParseException(RtpRejectReason.INVALID_EXTENSION,
                        "extension data length runs past end of packet");
            }
            byte[] extData = new byte[extDataLength];
            System.arraycopy(raw, offset + 4, extData, 0, extDataLength);
            headerExtension = new RtpHeaderExtension(profile, words, extData);
            offset += 4 + extDataLength;
        }

        if (!payloadTypes.isKnown(payloadType)) {
            throw new RtpParseException(RtpRejectReason.UNKNOWN_PAYLOAD_TYPE,
                    "payload type not accepted on this session: " + payloadType);
        }

        int paddingBytes = 0;
        int payloadEnd = length;
        if (padding) {
            if (offset >= length) {
                throw new RtpParseException(RtpRejectReason.INVALID_PADDING,
                        "padding flag set but no payload/padding bytes present");
            }
            paddingBytes = raw[length - 1] & 0xFF;
            if (paddingBytes <= 0 || paddingBytes > length - offset) {
                throw new RtpParseException(RtpRejectReason.INVALID_PADDING,
                        "padding length exceeds payload size: " + paddingBytes);
            }
            payloadEnd -= paddingBytes;
        }

        RtpHeader header = new RtpHeader(version, padding, extension, csrcCount,
                marker, payloadType, sequenceNumber, timestamp, ssrc, csrcs,
                headerExtension, offset);
        return new RtpPacket(header, raw, offset, payloadEnd - offset,
                paddingBytes, arrivalNanos);
    }

    private static long readUnsignedInt(byte[] buffer, int offset) {
        return ((long) (buffer[offset] & 0xFF) << 24)
                | ((buffer[offset + 1] & 0xFF) << 16)
                | ((buffer[offset + 2] & 0xFF) << 8)
                | (buffer[offset + 3] & 0xFF);
    }
}
