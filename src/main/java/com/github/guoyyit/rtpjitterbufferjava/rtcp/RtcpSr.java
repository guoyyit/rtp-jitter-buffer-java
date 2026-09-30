package com.github.guoyyit.rtpjitterbufferjava.rtcp;

import java.util.List;

/**
 * Parsed RTCP sender report.
 */
public class RtcpSr extends RtcpMessage {

    private final long senderSsrc;
    private final SenderInfo senderInfo;
    private final List<ReportBlock> reportBlocks;

    public RtcpSr(long senderSsrc, SenderInfo senderInfo, List<ReportBlock> reportBlocks) {
        this.senderSsrc = senderSsrc;
        this.senderInfo = senderInfo;
        this.reportBlocks = List.copyOf(reportBlocks);
    }

    public long senderSsrc() {
        return senderSsrc;
    }

    public SenderInfo senderInfo() {
        return senderInfo;
    }

    public List<ReportBlock> reportBlocks() {
        return reportBlocks;
    }
}
