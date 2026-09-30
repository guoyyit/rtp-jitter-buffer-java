package com.github.guoyyit.rtpjitterbufferjava.rtcp;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class RtcpCodec {

    public static final int SR = 200;
    public static final int RR = 201;
    public static final int BYE = 203;

    public List<RtcpPacket> parse(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("RTCP data is null");
        }
        return parse(data, 0, data.length);
    }

    public List<RtcpPacket> parse(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("RTCP bounds exceed the supplied buffer");
        }
        List<RtcpPacket> packets = new ArrayList<>();
        int cursor = offset;
        int end = offset + length;

        while (end - cursor >= 4) {
            int first = data[cursor] & 0xFF;
            int version = first >> 6;
            if (version != 2) {
                throw new IllegalArgumentException("unsupported RTCP version: " + version);
            }
            boolean padding = (first & 0x20) != 0;
            int itemCount = first & 0x1F;
            int packetType = data[cursor + 1] & 0xFF;
            int lengthWords = ((data[cursor + 2] & 0xFF) << 8) | (data[cursor + 3] & 0xFF);
            int packetLength = (lengthWords + 1) * 4;
            if (packetLength <= 0 || cursor + packetLength > end) {
                throw new IllegalArgumentException("RTCP packet length exceeds compound bounds");
            }

            ByteBuffer view = ByteBuffer.wrap(data, 0, cursor + packetLength)
                    .position(cursor).slice();
            view.position(4);
            RtcpPacket packet = switch (packetType) {
                case SR -> parseSenderReport(view, itemCount);
                case RR -> parseReceiverReport(view, itemCount);
                case BYE -> parseBye(view, itemCount);
                default -> new RtcpRaw(packetType,
                        java.util.Arrays.copyOfRange(data, cursor, cursor + packetLength));
            };
            packets.add(packet);

            int next = cursor + packetLength;
            if (padding && next == end) {
                int paddingLength = data[end - 1] & 0xFF;
                if (paddingLength > packetLength) {
                    throw new IllegalArgumentException("RTCP padding length exceeds packet bounds");
                }
            }
            cursor = next;
        }

        if (cursor != end) {
            throw new IllegalArgumentException("trailing " + (end - cursor) + " bytes in RTCP compound");
        }
        return packets;
    }

    private RtcpPacket parseSenderReport(ByteBuffer buffer, int blockCount) {
        long ssrc = readUnsignedInt(buffer);
        long ntpHigh = readUnsignedInt(buffer);
        long ntpLow = readUnsignedInt(buffer);
        long ntpTimestamp = (ntpHigh << 32) | ntpLow;
        long rtpTimestamp = readUnsignedInt(buffer);
        long packetCount = readUnsignedInt(buffer);
        long octetCount = readUnsignedInt(buffer);
        return new SenderReport(ssrc, ntpTimestamp, rtpTimestamp, packetCount, octetCount,
                parseBlocks(buffer, blockCount));
    }

    private RtcpPacket parseReceiverReport(ByteBuffer buffer, int blockCount) {
        long ssrc = readUnsignedInt(buffer);
        return new ReceiverReport(ssrc, parseBlocks(buffer, blockCount));
    }

    private RtcpPacket parseBye(ByteBuffer buffer, int ssrcCount) {
        long[] ssrcs = new long[ssrcCount];
        for (int i = 0; i < ssrcCount; i++) {
            ssrcs[i] = readUnsignedInt(buffer);
        }
        String reason = "";
        if (buffer.remaining() > 0) {
            int reasonLength = buffer.get() & 0xFF;
            if (reasonLength > buffer.remaining()) {
                throw new IllegalArgumentException("BYE reason length exceeds packet bounds");
            }
            byte[] reasonBytes = new byte[reasonLength];
            buffer.get(reasonBytes);
            reason = new String(reasonBytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return new RtcpBye(ssrcs, reason);
    }

    private List<ReportBlock> parseBlocks(ByteBuffer buffer, int blockCount) {
        List<ReportBlock> blocks = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            if (buffer.remaining() < ReportBlockIO.BLOCK_LENGTH) {
                throw new IllegalArgumentException("truncated RTCP report block");
            }
            long ssrc = readUnsignedInt(buffer);
            int fractionLost = buffer.get() & 0xFF;
            int lost24 = ((buffer.get() & 0xFF) << 16) | ((buffer.get() & 0xFF) << 8)
                    | (buffer.get() & 0xFF);
            long extendedHighest = readUnsignedInt(buffer);
            long jitter = readUnsignedInt(buffer);
            long lsr = readUnsignedInt(buffer);
            long dlsr = readUnsignedInt(buffer);
            blocks.add(new ReportBlock(ssrc, fractionLost, lost24, extendedHighest, jitter,
                    lsr, dlsr));
        }
        return blocks;
    }

    public byte[] encodeCompound(List<RtcpPacket> packets) {
        int total = packets.stream().mapToInt(RtcpPacket::byteLength).sum();
        ByteBuffer buffer = ByteBuffer.allocate(total);
        for (RtcpPacket packet : packets) {
            packet.writeTo(buffer);
        }
        return buffer.array();
    }

    private static long readUnsignedInt(ByteBuffer buffer) {
        return buffer.getInt() & 0xFFFFFFFFL;
    }
}
