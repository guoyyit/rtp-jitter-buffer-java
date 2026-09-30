package com.github.guoyyit.rtpjitterbufferjava.codec;

import com.github.guoyyit.rtpjitterbufferjava.model.RtpPacket;
import java.util.Arrays;

public class RtpParser {

    private static final int FIXED_HEADER_LENGTH = 12;
    private static final int CSRC_LENGTH = 4;
    private static final int EXTENSION_HEADER_LENGTH = 4;

    private final PayloadTypeRegistry registry;

    public RtpParser(PayloadTypeRegistry registry) {
        this.registry = registry;
    }

    public RtpPacket parse(byte[] data) {
        if (data == null) {
            throw new RtpFormatException(RejectReason.TRUNCATED_HEADER, "packet data is null");
        }
        return parse(data, 0, data.length);
    }

    public RtpPacket parse(byte[] data, int offset, int length) {
        if (data == null) {
            throw new RtpFormatException(RejectReason.TRUNCATED_HEADER, "packet data is null");
        }
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new RtpFormatException(RejectReason.TRUNCATED_HEADER,
                    "packet bounds exceed the supplied buffer");
        }
        if (length < FIXED_HEADER_LENGTH) {
            throw new RtpFormatException(RejectReason.TRUNCATED_HEADER,
                    "packet shorter than the fixed 12-byte RTP header: " + length);
        }

        int first = data[offset] & 0xFF;
        int version = first >> 6;
        if (version != 2) {
            throw new RtpFormatException(RejectReason.VERSION_MISMATCH,
                    "unsupported RTP version: " + version);
        }
        boolean padding = (first & 0x20) != 0;
        boolean extension = (first & 0x10) != 0;
        int csrcCount = first & 0x0F;

        int second = data[offset + 1] & 0xFF;
        boolean marker = (second & 0x80) != 0;
        int payloadType = second & 0x7F;
        int sequence = readUnsignedShort(data, offset + 2);
        long timestamp = readUnsignedInt(data, offset + 4);
        long ssrc = readUnsignedInt(data, offset + 8);

        if (!registry.isKnown(payloadType)) {
            throw new RtpFormatException(RejectReason.PAYLOAD_TYPE_UNKNOWN,
                    "unknown payload type: " + payloadType);
        }

        int cursor = offset + FIXED_HEADER_LENGTH;
        long[] csrc = new long[csrcCount];
        for (int i = 0; i < csrcCount; i++) {
            if (cursor + CSRC_LENGTH > offset + length) {
                throw new RtpFormatException(RejectReason.INVALID_CSRC_LENGTH,
                        "packet is truncated inside the CSRC list");
            }
            csrc[i] = readUnsignedInt(data, cursor);
            cursor += CSRC_LENGTH;
        }

        com.github.guoyyit.rtpjitterbufferjava.model.RtpExtension headerExtension = null;
        if (extension) {
            if (cursor + EXTENSION_HEADER_LENGTH > offset + length) {
                throw new RtpFormatException(RejectReason.INVALID_EXTENSION,
                        "packet is truncated inside the extension header");
            }
            int profileSpecific = readUnsignedShort(data, cursor);
            int extensionWords = readUnsignedShort(data, cursor + 2);
            int extensionLength = extensionWords * 4;
            cursor += EXTENSION_HEADER_LENGTH;
            if (cursor + extensionLength > offset + length) {
                throw new RtpFormatException(RejectReason.INVALID_EXTENSION,
                        "extension data length exceeds packet bounds");
            }
            byte[] extensionData = Arrays.copyOfRange(data, cursor, cursor + extensionLength);
            headerExtension = new com.github.guoyyit.rtpjitterbufferjava.model.RtpExtension(
                    profileSpecific, extensionData);
            cursor += extensionLength;
        }

        int payloadEnd = offset + length;
        if (padding) {
            int paddingLength = data[payloadEnd - 1] & 0xFF;
            if (paddingLength <= 0 || cursor + paddingLength > payloadEnd) {
                throw new RtpFormatException(RejectReason.INVALID_PADDING,
                        "padding length exceeds packet bounds: " + paddingLength);
            }
            payloadEnd -= paddingLength;
        }

        if (payloadEnd < cursor) {
            throw new RtpFormatException(RejectReason.TRUNCATED_HEADER, "packet has no payload space");
        }

        byte[] raw = Arrays.copyOfRange(data, offset, offset + length);
        return new RtpPacket(padding, extension, csrcCount, marker, payloadType, sequence,
                timestamp, ssrc, csrc, headerExtension, cursor - offset,
                payloadEnd - cursor, raw);
    }

    private static int readUnsignedShort(byte[] data, int index) {
        return ((data[index] & 0xFF) << 8) | (data[index + 1] & 0xFF);
    }

    private static long readUnsignedInt(byte[] data, int index) {
        return ((long) (data[index] & 0xFF) << 24)
                | ((data[index + 1] & 0xFF) << 16)
                | ((data[index + 2] & 0xFF) << 8)
                | (data[index + 3] & 0xFF);
    }
}
