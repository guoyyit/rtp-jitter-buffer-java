package com.github.guoyyit.rtpjitterbufferjava.tests;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Small binary builders for RTP and RTCP test datagrams.
 */
final class RtpTestPackets {

    static final int PT_PCMU = 0;
    static final int PT_DYNAMIC = 96;

    private RtpTestPackets() {
    }

    static byte[] rtp(int payloadType, int sequence, long timestamp, long ssrc, byte[] payload) {
        return build(2, false, false, payloadType, sequence, timestamp, ssrc, payload,
                null, 0, 0);
    }

    static byte[] rtp(int version, int payloadType, int sequence, long timestamp, long ssrc,
                      byte[] payload) {
        return build(version, false, false, payloadType, sequence, timestamp, ssrc, payload,
                null, 0, 0);
    }

    static byte[] rtpWithExtension(int payloadType, int sequence, long timestamp, long ssrc,
                                   byte[] payload, int profile, byte[] extensionData) {
        int words = (extensionData.length + 3) / 4;
        return build(2, true, false, payloadType, sequence, timestamp, ssrc, payload,
                new Extension(profile, extensionData, words), 0, 0);
    }

    static byte[] rtpWithExtensionWords(int payloadType, int sequence, long timestamp, long ssrc,
                                        byte[] payload, int profile, byte[] extensionData,
                                        int declaredWords) {
        return build(2, true, false, payloadType, sequence, timestamp, ssrc, payload,
                new Extension(profile, extensionData, declaredWords), 0, 0);
    }

    static byte[] paddedRtp(int payloadType, int sequence, long timestamp, long ssrc,
                            byte[] payload, int padding) {
        return build(2, false, true, payloadType, sequence, timestamp, ssrc, payload,
                null, 0, padding);
    }

    static byte[] rtpWithBadPadding(int payloadType, int sequence, long timestamp, long ssrc,
                                    byte[] payload, int declaredPadding, int actualPaddingBytes) {
        byte[] base = build(2, false, true, payloadType, sequence, timestamp, ssrc, payload,
                null, 0, 0);
        byte[] result = new byte[base.length + actualPaddingBytes];
        System.arraycopy(base, 0, result, 0, base.length);
        for (int i = 0; i < actualPaddingBytes - 1; i++) {
            result[base.length + i] = 0;
        }
        result[result.length - 1] = (byte) declaredPadding;
        return result;
    }

    private record Extension(int profile, byte[] data, int declaredWords) {
    }

    private static byte[] build(int version, boolean extension, boolean paddingFlag,
                                int payloadType, int sequence, long timestamp, long ssrc,
                                byte[] payload, Extension ext, int csrcCount, int padding) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((version << 6) | (extension ? 0x10 : 0)
                | ((paddingFlag || padding > 0) ? 0x20 : 0) | (csrcCount & 0x0F));
        out.write(payloadType & 0x7F);
        out.write((sequence >>> 8) & 0xFF);
        out.write(sequence & 0xFF);
        writeInt(out, (int) timestamp);
        writeInt(out, (int) ssrc);
        if (ext != null) {
            out.write((ext.profile() >>> 8) & 0xFF);
            out.write(ext.profile() & 0xFF);
            out.write((ext.declaredWords() >>> 8) & 0xFF);
            out.write(ext.declaredWords() & 0xFF);
            out.writeBytes(ext.data());
        }
        if (payload != null) {
            out.writeBytes(payload);
        }
        for (int i = 0; i < padding - 1; i++) {
            out.write(0);
        }
        if (padding > 0) {
            out.write(padding);
        }
        return out.toByteArray();
    }

    static byte[] receiverReport(long ssrc, long[][] blocks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x80 | (blocks.length & 0x1F));
        out.write(201);
        int lengthWordsMinusOne = 1 + blocks.length * 6;
        out.write((lengthWordsMinusOne >>> 8) & 0xFF);
        out.write(lengthWordsMinusOne & 0xFF);
        writeInt(out, (int) ssrc);
        for (long[] block : blocks) {
            writeReportBlock(out, block);
        }
        return out.toByteArray();
    }

    static byte[] senderReport(long ssrc, long ntp, long rtpTimestamp, long packets, long octets) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x80);
        out.write(200);
        out.write(0x00);
        out.write(0x06);
        writeInt(out, (int) ssrc);
        writeLong(out, ntp);
        writeInt(out, (int) rtpTimestamp);
        writeInt(out, (int) packets);
        writeInt(out, (int) octets);
        return out.toByteArray();
    }

    static byte[] bye(long ssrc, String reason) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        int contentLength = 4 + 4 + 1 + reasonBytes.length;
        int padded = (contentLength + 3) & ~3;
        int wordsMinusOne = padded / 4 - 1;
        out.write(0x81);
        out.write(203);
        out.write((wordsMinusOne >>> 8) & 0xFF);
        out.write(wordsMinusOne & 0xFF);
        writeInt(out, (int) ssrc);
        out.write(reasonBytes.length);
        out.writeBytes(reasonBytes);
        while (out.size() < padded) {
            out.write(0);
        }
        return out.toByteArray();
    }

    static byte[] unknownRtcp(int packetType) {
        return new byte[] {
                (byte) 0x80, (byte) packetType, 0x00, 0x01,
                0, 0, 0, 0
        };
    }

    private static void writeReportBlock(ByteArrayOutputStream out, long[] block) {
        writeInt(out, (int) block[0]);
        out.write((int) block[1] & 0xFF);
        int lost = (int) block[2];
        out.write((lost >>> 16) & 0xFF);
        out.write((lost >>> 8) & 0xFF);
        out.write(lost & 0xFF);
        writeInt(out, (int) block[3]);
        writeInt(out, (int) block[4]);
        writeInt(out, (int) block[5]);
        writeInt(out, (int) block[6]);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (value >>> shift) & 0xFF);
        }
    }
}
