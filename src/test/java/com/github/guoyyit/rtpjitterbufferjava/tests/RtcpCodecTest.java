package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.rtcp.ReceiverReport;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.ReportBlock;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpBye;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpCodec;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacket;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpRaw;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.SenderReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RtcpCodecTest {

    private final RtcpCodec codec = new RtcpCodec();

    @Test
    void parsesReceiverReportWithBlocks() {
        long[] block = {1234L, 25, 7, 100L, 9L, 0x12345678L, 500L};
        byte[] data = RtpTestPackets.receiverReport(42L, new long[][] {block});

        List<RtcpPacket> packets = codec.parse(data);

        assertEquals(1, packets.size());
        ReceiverReport rr = assertInstanceOf(ReceiverReport.class, packets.get(0));
        assertEquals(42L, rr.ssrc());
        assertEquals(1, rr.reportBlocks().size());

        ReportBlock parsed = rr.reportBlocks().get(0);
        assertEquals(1234L, parsed.ssrc());
        assertEquals(25, parsed.fractionLost());
        assertEquals(7, parsed.cumulativeLost());
        assertEquals(100L, parsed.extendedHighestSequence());
        assertEquals(9L, parsed.jitter());
        assertEquals(0x12345678L, parsed.lastSenderReport());
        assertEquals(500L, parsed.delaySinceLastSenderReport());
    }

    @Test
    void parsesSenderReport() {
        long ntp = 0xE000_0000_0000_0001L;
        byte[] data = RtpTestPackets.senderReport(77L, ntp, 5000L, 11L, 220L);

        SenderReport sr = assertInstanceOf(SenderReport.class, codec.parse(data).get(0));

        assertEquals(77L, sr.ssrc());
        assertEquals(ntp, sr.ntpTimestamp());
        assertEquals(5000L, sr.rtpTimestamp());
        assertEquals(11L, sr.packetCount());
        assertEquals(220L, sr.octetCount());
    }

    @Test
    void parsesByeWithReason() {
        byte[] data = RtpTestPackets.bye(99L, "gone");

        RtcpBye bye = assertInstanceOf(RtcpBye.class, codec.parse(data).get(0));

        assertEquals(99L, bye.ssrcs()[0]);
        assertEquals("gone", bye.reason());
    }

    @Test
    void encodesAndReparsesGeneratedReports() {
        ReportBlock block = new ReportBlock(55L, 10, 3, 90L, 4L, 0xAABBCCDDL, 12L);
        ReceiverReport report = new ReceiverReport(88L, List.of(block));

        byte[] encoded = codec.encodeCompound(List.of(report));

        ReceiverReport reparsed = assertInstanceOf(ReceiverReport.class, codec.parse(encoded).get(0));
        assertEquals(88L, reparsed.ssrc());
        assertEquals(block, reparsed.reportBlocks().get(0));
    }

    @Test
    void preservesUnknownRtcpTypesAsRaw() {
        byte[] data = RtpTestPackets.unknownRtcp(202);

        RtcpPacket packet = codec.parse(data).get(0);

        assertEquals(202, packet.packetType());
        assertInstanceOf(RtcpRaw.class, packet);
    }

    @Test
    void rejectsTruncatedCompound() {
        byte[] data = {(byte) 0x80, (byte) 201, 0x00, 0x05, 0, 0, 0, 0};

        assertThrows(IllegalArgumentException.class, () -> codec.parse(data));
    }

    @Test
    void rejectsWrongVersion() {
        byte[] data = RtpTestPackets.unknownRtcp(202);
        data[0] = 0x00;

        assertThrows(IllegalArgumentException.class, () -> codec.parse(data));
    }
}
