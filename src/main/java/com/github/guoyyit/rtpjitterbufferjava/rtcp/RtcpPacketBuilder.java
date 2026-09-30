package com.github.guoyyit.rtpjitterbufferjava.rtcp;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds compound RTCP datagrams carrying RR/SR plus an SDES CNAME chunk.
 */
public class RtcpPacketBuilder {

    public byte[] buildReceiverReport(long senderSsrc,
                                      String cname,
                                      List<ReportBlock> blocks) {
        byte[] rr = buildReportHeader(RtcpMessage.PT_RR, senderSsrc, blocks, null);
        byte[] sdes = buildSdes(senderSsrc, cname);
        return concatenate(rr, sdes);
    }

    public byte[] buildSenderReport(long senderSsrc,
                                    String cname,
                                    SenderInfo senderInfo,
                                    List<ReportBlock> blocks) {
        byte[] sr = buildReportHeader(RtcpMessage.PT_SR, senderSsrc, blocks, senderInfo);
        byte[] sdes = buildSdes(senderSsrc, cname);
        return concatenate(sr, sdes);
    }

    private byte[] buildReportHeader(int payloadType, long senderSsrc,
                                     List<ReportBlock> blocks, SenderInfo senderInfo) {
        int fixedLength = payloadType == RtcpMessage.PT_SR ? 28 : 8;
        int length = fixedLength + blocks.size() * 24;
        byte[] packet = new byte[length];
        packet[0] = (byte) (0x80 | (blocks.size() & 0x1F));
        packet[1] = (byte) payloadType;
        int words = length / 4 - 1;
        packet[2] = (byte) (words >> 8);
        packet[3] = (byte) words;
        ReportBlock.writeUInt32(packet, 4, senderSsrc);

        int offset = 8;
        if (payloadType == RtcpMessage.PT_SR) {
            ReportBlock.writeUInt32(packet, 8, senderInfo.ntpTimestamp() >>> 32);
            ReportBlock.writeUInt32(packet, 12, senderInfo.ntpTimestamp() & 0xFFFFFFFFL);
            ReportBlock.writeUInt32(packet, 16, senderInfo.rtpTimestamp());
            ReportBlock.writeUInt32(packet, 20, senderInfo.packetCount());
            ReportBlock.writeUInt32(packet, 24, senderInfo.octetCount());
            offset = 28;
        }
        for (ReportBlock block : blocks) {
            block.writeTo(packet, offset);
            offset += 24;
        }
        return packet;
    }

    private byte[] buildSdes(long ssrc, String cname) {
        byte[] cnameBytes = cname.getBytes(StandardCharsets.UTF_8);
        int chunkLength = 4 + 2 + cnameBytes.length;
        while (chunkLength % 4 != 0) {
            chunkLength++;
        }
        byte[] packet = new byte[4 + chunkLength];
        packet[0] = (byte) (0x80 | 1);
        packet[1] = (byte) RtcpMessage.PT_SDES;
        int words = packet.length / 4 - 1;
        packet[2] = (byte) (words >> 8);
        packet[3] = (byte) words;
        ReportBlock.writeUInt32(packet, 4, ssrc);
        packet[8] = 1;
        packet[9] = (byte) cnameBytes.length;
        System.arraycopy(cnameBytes, 0, packet, 10, cnameBytes.length);
        return packet;
    }

    private byte[] concatenate(byte[] first, byte[] second) {
        byte[] combined = new byte[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }
}
