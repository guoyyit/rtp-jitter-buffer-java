package com.github.guoyyit.rtpjitterbufferjava.rtcp;

public record ReportBlock(
        long ssrc,
        int fractionLost,
        int cumulativeLost,
        long extendedHighestSequence,
        long jitter,
        long lastSenderReport,
        long delaySinceLastSenderReport
) {
}
