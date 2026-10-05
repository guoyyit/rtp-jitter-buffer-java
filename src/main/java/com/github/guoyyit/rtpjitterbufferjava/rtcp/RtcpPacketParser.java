package com.github.guoyyit.rtpjitterbufferjava.rtcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses compound RTCP datagrams into {@link RtcpMessage} objects (RFC 3550 6).
 */
public class RtcpPacketParser {

    /**
     * Demultiplexes RTCP from RTP on a shared channel (RFC 5761 section 4):
     * RTCP packet types 200..204 never collide with RTP payload types because
     * the marker bit separates the ranges.
     */
    public static boolean isRtcp(byte[] raw, int length) {
        if (raw == null || length < 2 || raw.length < 2) {
            return false;
        }
        int version = (raw[0] >> 6) & 0x03;
        int packetType = raw[1] & 0xFF;
        return version == 2
                && packetType >= RtcpMessage.PT_SR
                && packetType <= RtcpMessage.PT_APP;
    }

    public List<RtcpMessage> parse(byte[] raw, int length) {
        if (raw == null || length < 4 || raw.length < length) {
            throw new IllegalArgumentException("truncated RTCP packet");
        }
        List<RtcpMessage> messages = new ArrayList<>();
        int offset = 0;
        while (offset + 4 <= length) {
            int first = raw[offset] & 0xFF;
            int version = (first >> 6) & 0x03;
            if (version != 2) {
                throw new IllegalArgumentException("unsupported RTCP version: " + version);
            }
            int padding = (first & 0x20) != 0 ? (raw[length - 1] & 0xFF) : 0;
            int itemCount = first & 0x1F;
            int payloadType = raw[offset + 1] & 0xFF;
            int wordLength = ((raw[offset + 2] & 0xFF) << 8) | (raw[offset + 3] & 0xFF);
            int packetLength = (wordLength + 1) * 4;
            if (packetLength <= 0 || offset + packetLength > length) {
                throw new IllegalArgumentException("RTCP item length runs past datagram");
            }

            switch (payloadType) {
                case RtcpMessage.PT_SR -> messages.add(parseSr(raw, offset, itemCount));
                case RtcpMessage.PT_RR -> messages.add(parseRr(raw, offset, itemCount));
                case RtcpMessage.PT_SDES -> messages.add(parseSdes(raw, offset, packetLength));
                default -> {
                    // BYE/APP and future types are skipped but must still be well-formed.
                }
            }
            offset += packetLength;
            if (padding > 0) {
                break;
            }
        }
        return messages;
    }

    private RtcpSr parseSr(byte[] raw, int offset, int blockCount) {
        long senderSsrc = ReportBlock.readUInt32(raw, offset + 4);
        long ntp = (ReportBlock.readUInt32(raw, offset + 8) << 32)
                | ReportBlock.readUInt32(raw, offset + 12);
        long rtpTimestamp = ReportBlock.readUInt32(raw, offset + 16);
        long packetCount = ReportBlock.readUInt32(raw, offset + 20);
        long octetCount = ReportBlock.readUInt32(raw, offset + 24);
        SenderInfo senderInfo = new SenderInfo(ntp, rtpTimestamp, packetCount, octetCount);
        List<ReportBlock> blocks = parseBlocks(raw, offset + 28, blockCount);
        return new RtcpSr(senderSsrc, senderInfo, blocks);
    }

    private RtcpRr parseRr(byte[] raw, int offset, int blockCount) {
        long senderSsrc = ReportBlock.readUInt32(raw, offset + 4);
        List<ReportBlock> blocks = parseBlocks(raw, offset + 8, blockCount);
        return new RtcpRr(senderSsrc, blocks);
    }

    private List<ReportBlock> parseBlocks(byte[] raw, int offset, int blockCount) {
        List<ReportBlock> blocks = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            blocks.add(ReportBlock.readFrom(raw, offset + i * 24));
        }
        return blocks;
    }

    private RtcpSdes parseSdes(byte[] raw, int offset, int packetLength) {
        Map<Long, String> cnames = new HashMap<>();
        int position = offset + 4;
        int end = offset + packetLength;
        while (position + 4 <= end) {
            long ssrc = ReportBlock.readUInt32(raw, position);
            position += 4;
            while (position < end) {
                int type = raw[position] & 0xFF;
                position++;
                if (type == 0) {
                    break;
                }
                int itemLength = raw[position] & 0xFF;
                position++;
                if (position + itemLength > end) {
                    throw new IllegalArgumentException("SDES item runs past packet");
                }
                if (type == 1) {
                    cnames.put(ssrc, new String(raw, position, itemLength, StandardCharsets.UTF_8));
                }
                position += itemLength;
            }
            position = (position + 4) & ~3;
        }
        return new RtcpSdes(cnames);
    }
}
