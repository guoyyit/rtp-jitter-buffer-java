package com.github.guoyyit.rtpjitterbufferjava.rtcp;

import java.nio.ByteBuffer;
import java.util.List;

public final class SenderReport extends RtcpPacket {

    public static final int PT = 200;

    private final long ssrc;
    private final long ntpTimestamp;
    private final long rtpTimestamp;
    private final long packetCount;
    private final long octetCount;
    private final List<ReportBlock> reportBlocks;

    public SenderReport(long ssrc, long ntpTimestamp, long rtpTimestamp, long packetCount,
                        long octetCount, List<ReportBlock> reportBlocks) {
        this.ssrc = ssrc;
        this.ntpTimestamp = ntpTimestamp;
        this.rtpTimestamp = rtpTimestamp;
        this.packetCount = packetCount;
        this.octetCount = octetCount;
        this.reportBlocks = List.copyOf(reportBlocks);
    }

    public long ssrc() {
        return ssrc;
    }

    public long ntpTimestamp() {
        return ntpTimestamp;
    }

    public long rtpTimestamp() {
        return rtpTimestamp;
    }

    public long packetCount() {
        return packetCount;
    }

    public long octetCount() {
        return octetCount;
    }

    public List<ReportBlock> reportBlocks() {
        return reportBlocks;
    }

    @Override
    public int packetType() {
        return PT;
    }

    @Override
    public int byteLength() {
        return 28 + reportBlocks.size() * ReportBlockIO.BLOCK_LENGTH;
    }

    @Override
    public void writeTo(ByteBuffer buffer) {
        ReportBlockIO.writeHeader(buffer, PT, reportBlocks.size(), byteLength(), ssrc);
        buffer.putInt((int) (ntpTimestamp >>> 32));
        buffer.putInt((int) ntpTimestamp);
        buffer.putInt((int) rtpTimestamp);
        buffer.putInt((int) packetCount);
        buffer.putInt((int) octetCount);
        for (ReportBlock block : reportBlocks) {
            ReportBlockIO.writeBlock(buffer, block);
        }
    }
}
