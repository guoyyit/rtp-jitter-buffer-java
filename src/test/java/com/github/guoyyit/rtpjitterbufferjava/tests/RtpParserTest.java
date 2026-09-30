package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.codec.PayloadTypeRegistry;
import com.github.guoyyit.rtpjitterbufferjava.codec.RejectReason;
import com.github.guoyyit.rtpjitterbufferjava.codec.RtpFormatException;
import com.github.guoyyit.rtpjitterbufferjava.codec.RtpParser;
import com.github.guoyyit.rtpjitterbufferjava.model.RtpPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpParserTest {

    private final RtpParser parser = new RtpParser(PayloadTypeRegistry.rfc3551());

    @Test
    void parsesValidPacket() {
        byte[] payload = {1, 2, 3, 4};
        byte[] data = RtpTestPackets.rtp(RtpTestPackets.PT_PCMU, 42, 1000L, 0xCAFEBABEL, payload);

        RtpPacket packet = parser.parse(data);

        assertEquals(RtpTestPackets.PT_PCMU, packet.payloadType());
        assertEquals(42, packet.sequence());
        assertEquals(1000L, packet.timestamp());
        assertEquals(0xCAFEBABEL, packet.ssrc());
        assertEquals(12, packet.payloadOffset());
        assertArrayEquals(payload, packet.payloadSlice());
        assertFalse(packet.padding());
    }

    @Test
    void parsesHeaderExtension() {
        byte[] extensionData = {0, 1, 0, (byte) 0xAA};
        byte[] data = RtpTestPackets.rtpWithExtension(RtpTestPackets.PT_PCMU, 1, 100, 9,
                new byte[] {9}, 0xBEDE, extensionData);

        RtpPacket packet = parser.parse(data);

        assertTrue(packet.extension());
        assertEquals(0xBEDE, packet.headerExtension().profileSpecific());
        assertArrayEquals(extensionData, packet.headerExtension().data());
    }

    @Test
    void stripsPaddingAndKeepsPayload() {
        byte[] payload = {10, 20};
        byte[] data = RtpTestPackets.paddedRtp(RtpTestPackets.PT_PCMU, 1, 100, 9, payload, 4);

        RtpPacket packet = parser.parse(data);

        assertTrue(packet.padding());
        assertArrayEquals(payload, packet.payloadSlice());
    }

    @Test
    void rejectsWrongVersion() {
        byte[] data = RtpTestPackets.rtp(0, RtpTestPackets.PT_PCMU, 1, 1, 1, new byte[] {1});

        RtpFormatException error = assertThrows(RtpFormatException.class, () -> parser.parse(data));
        assertEquals(RejectReason.VERSION_MISMATCH, error.getReason());
    }

    @Test
    void rejectsTruncatedHeader() {
        byte[] data = {(byte) 0x80, 0x00, 0x00, 0x01};

        RtpFormatException error = assertThrows(RtpFormatException.class, () -> parser.parse(data));
        assertEquals(RejectReason.TRUNCATED_HEADER, error.getReason());
    }

    @Test
    void rejectsTruncatedExtension() {
        byte[] data = RtpTestPackets.rtpWithExtensionWords(RtpTestPackets.PT_PCMU, 1, 1, 1,
                new byte[] {1}, 0xBEDE, new byte[] {1, 2, 3}, 2);

        RtpFormatException error = assertThrows(RtpFormatException.class, () -> parser.parse(data));
        assertEquals(RejectReason.INVALID_EXTENSION, error.getReason());
    }

    @Test
    void rejectsBadPadding() {
        byte[] data = RtpTestPackets.rtpWithBadPadding(RtpTestPackets.PT_PCMU, 1, 1, 1,
                new byte[] {1}, 8, 3);

        RtpFormatException error = assertThrows(RtpFormatException.class, () -> parser.parse(data));
        assertEquals(RejectReason.INVALID_PADDING, error.getReason());
    }

    @Test
    void rejectsUnknownPayloadType() {
        PayloadTypeRegistry registry = new PayloadTypeRegistry(java.util.Set.of(0), 8000);
        RtpParser strictParser = new RtpParser(registry);
        byte[] data = RtpTestPackets.rtp(RtpTestPackets.PT_DYNAMIC, 1, 1, 1, new byte[] {1});

        RtpFormatException error =
                assertThrows(RtpFormatException.class, () -> strictParser.parse(data));
        assertEquals(RejectReason.PAYLOAD_TYPE_UNKNOWN, error.getReason());
    }
}
