package com.github.guoyyit.rtpjitterbufferjava.rtcp;

import java.util.List;

/**
 * Parsed RTCP receiver report.
 */
public class RtcpRr extends RtcpMessage {

    private final long senderSsrc;
    private final List<ReportBlock> reportBlocks;

    public RtcpRr(long senderSsrc, List<ReportBlock> reportBlocks) {
        this.senderSsrc = senderSsrc;
        this.reportBlocks = List.copyOf(reportBlocks);
    }

    public long senderSsrc() {
        return senderSsrc;
    }

    public List<ReportBlock> reportBlocks() {
        return reportBlocks;
    }
}
