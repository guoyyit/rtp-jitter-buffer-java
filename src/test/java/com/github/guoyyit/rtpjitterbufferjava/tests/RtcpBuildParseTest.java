package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.rtcp.NtpTime;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.ReportBlock;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpMessage;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacketBuilder;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacketParser;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpRr;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpSr;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.SenderInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtcpBuildParseTest {

    private final RtcpPacketBuilder builder = new RtcpPacketBuilder();
    private final RtcpPacketParser parser = new RtcpPacketParser();

    private static ReportBlock sampleBlock() {
        return new ReportBlock(0xCAFEBABEL, 25, 7, 0x00010002L, 1234,
                NtpTime.middle32(0x1122334455667788L), 0x00010000L);
    }

    @Test
    void buildsAndParsesReceiverReportWithCname() {
        byte[] compound = builder.buildReceiverReport(42, "alice@host", List.of(sampleBlock()));
        List<RtcpMessage> messages = parser.parse(compound, compound.length);

        assertEquals(2, messages.size());
        RtcpRr rr = (RtcpRr) messages.get(0);
        assertEquals(42, rr.senderSsrc());
        assertEquals(1, rr.reportBlocks().size());
        ReportBlock block = rr.reportBlocks().get(0);
        assertEquals(0xCAFEBABEL, block.ssrc());
        assertEquals(25, block.fractionLost());
        assertEquals(7, block.cumulativePacketsLost());
        assertEquals(0x00010002L, block.extendedHighestSequence());
        assertEquals(1234, block.interarrivalJitter());
    }

    @Test
    void buildsAndParsesSenderReport() {
        long ntp = NtpTime.now();
        SenderInfo info = new SenderInfo(ntp, 160, 10, 1000);
        byte[] compound = builder.buildSenderReport(7, "bob@host", info, List.of());
        List<RtcpMessage> messages = parser.parse(compound, compound.length);

        RtcpSr sr = (RtcpSr) messages.get(0);
        assertEquals(7, sr.senderSsrc());
        assertEquals(160, sr.senderInfo().rtpTimestamp());
        assertEquals(10, sr.senderInfo().packetCount());
        assertEquals(1000, sr.senderInfo().octetCount());
        assertEquals(ntp, sr.senderInfo().ntpTimestamp());
        assertTrue(sr.reportBlocks().isEmpty());
    }

    @Test
    void rejectsWrongVersionAndBadLength() {
        byte[] compound = builder.buildReceiverReport(1, "c", List.of());
        compound[0] = 0x40;
        assertThrowsIllegalArgument(compound, compound.length);

        byte[] truncated = builder.buildSenderReport(1, "c",
                new SenderInfo(NtpTime.now(), 0, 0, 0), List.of());
        assertThrowsIllegalArgument(truncated, 6);
    }

    private void assertThrowsIllegalArgument(byte[] data, int length) {
        boolean threw = false;
        try {
            parser.parse(data, length);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue(threw, "expected parse rejection");
    }
}
