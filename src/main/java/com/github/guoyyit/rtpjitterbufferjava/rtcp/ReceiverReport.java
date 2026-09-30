package com.github.guoyyit.rtpjitterbufferjava.rtcp;

import java.nio.ByteBuffer;
import java.util.List;

public final class ReceiverReport extends RtcpPacket {

    public static final int PT = 201;

    private final long ssrc;
    private final List<ReportBlock> reportBlocks;

    public ReceiverReport(long ssrc, List<ReportBlock> reportBlocks) {
        this.ssrc = ssrc;
        this.reportBlocks = List.copyOf(reportBlocks);
    }

    public long ssrc() {
        return ssrc;
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
        return 8 + reportBlocks.size() * ReportBlockIO.BLOCK_LENGTH;
    }

    @Override
    public void writeTo(ByteBuffer buffer) {
        ReportBlockIO.writeHeader(buffer, PT, reportBlocks.size(), byteLength(), ssrc);
        for (ReportBlock block : reportBlocks) {
            ReportBlockIO.writeBlock(buffer, block);
        }
    }
}
