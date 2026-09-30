package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPacket;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPacketParser;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpParseException;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPayloadTypes;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpRejectReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpPacketParserTest {

    private final RtpPacketParser parser = new RtpPacketParser(RtpPayloadTypes.all());

    static byte[] rtpPacket(int payloadType, int seq, long timestamp, long ssrc,
                            boolean marker, byte[] payload) {
        byte[] raw = new byte[12 + payload.length];
        raw[0] = (byte) 0x80;
        raw[1] = (byte) ((marker ? 0x80 : 0) | (payloadType & 0x7F));
        raw[2] = (byte) (seq >> 8);
        raw[3] = (byte) seq;
        raw[4] = (byte) (timestamp >> 24);
        raw[5] = (byte) (timestamp >> 16);
        raw[6] = (byte) (timestamp >> 8);
        raw[7] = (byte) timestamp;
        raw[8] = (byte) (ssrc >> 24);
        raw[9] = (byte) (ssrc >> 16);
        raw[10] = (byte) (ssrc >> 8);
        raw[11] = (byte) ssrc;
        System.arraycopy(payload, 0, raw, 12, payload.length);
        return raw;
    }

    @Test
    void parsesValidHeaderAndPayload() {
        byte[] raw = rtpPacket(96, 0x1234, 0xAABBCCDDL, 0x01020304L, true,
                new byte[]{1, 2, 3, 4});
        RtpPacket packet = parser.parse(raw, raw.length, 1000);

        assertEquals(2, packet.header().version());
        assertEquals(96, packet.header().payloadType());
        assertEquals(0x1234, packet.header().sequenceNumber());
        assertEquals(0xAABBCCDDL, packet.header().timestamp());
        assertEquals(0x01020304L, packet.header().ssrc());
        assertTrue(packet.header().marker());
        assertEquals(12, packet.header().headerLength());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, packet.payload());
        assertEquals(1000, packet.arrivalNanos());
    }

    @Test
    void rejectsVersionMismatch() {
        byte[] raw = rtpPacket(0, 1, 0, 1, false, new byte[1]);
        raw[0] = 0x40;
        RtpParseException error = assertThrows(RtpParseException.class,
                () -> parser.parse(raw, raw.length, 0));
        assertEquals(RtpRejectReason.VERSION_MISMATCH, error.getReason());
    }

    @Test
    void rejectsTruncatedPacket() {
        byte[] raw = rtpPacket(0, 1, 0, 1, false, new byte[1]);
        RtpParseException error = assertThrows(RtpParseException.class,
                () -> parser.parse(raw, 8, 0));
        assertEquals(RtpRejectReason.TRUNCATED_PACKET, error.getReason());
    }

    @Test
    void rejectsUnknownPayloadType() {
        RtpPacketParser strict = new RtpPacketParser(RtpPayloadTypes.of(0));
        byte[] raw = rtpPacket(96, 1, 0, 1, false, new byte[1]);
        RtpParseException error = assertThrows(RtpParseException.class,
                () -> strict.parse(raw, raw.length, 0));
        assertEquals(RtpRejectReason.UNKNOWN_PAYLOAD_TYPE, error.getReason());
        assertFalse(parser.parse(rtpPacket(96, 1, 0, 1, false, new byte[1]), 13, 0)
                .header().padding());
    }

    @Test
    void parsesCsrcAndExtension() {
        byte[] raw = new byte[12 + 8 + 12 + 2];
        raw[0] = (byte) 0x92; // V=2, X=1, CC=2
        raw[1] = 0;
        raw[20] = (byte) 0xAA;
        raw[21] = (byte) 0xBB;
        raw[24] = (byte) 0xDD;
        raw[25] = (byte) 0xEE;
        raw[22] = 0;
        raw[23] = 2; // extension length in 32-bit words

        RtpPacket packet = parser.parse(raw, raw.length, 0);
        assertEquals(2, packet.header().csrcCount());
        assertEquals(2, packet.header().csrcs().size());
        assertEquals(0xAABBL, packet.header().headerExtension().profileType());
        assertEquals(2, packet.header().headerExtension().wordCount());
        assertArrayEquals(new byte[]{(byte) 0xDD, (byte) 0xEE, 0, 0, 0, 0, 0, 0},
                packet.header().headerExtension().data());
        assertEquals(12 + 8 + 12, packet.header().headerLength());
        assertEquals(2, packet.payload().length);
    }

    @Test
    void rejectsBadExtensionLength() {
        byte[] raw = new byte[12 + 8];
        raw[0] = (byte) 0x90;
        raw[14] = 1; // claims 4 extension bytes but datagram ends right after prefix
        RtpParseException error = assertThrows(RtpParseException.class,
                () -> parser.parse(raw, raw.length, 0));
        assertEquals(RtpRejectReason.INVALID_EXTENSION, error.getReason());
    }

    @Test
    void stripsValidPaddingAndRejectsBadPadding() {
        byte[] raw = rtpPacket(0, 1, 0, 1, false, new byte[]{9, 0, 0, 3});
        raw[0] |= 0x20;
        RtpPacket packet = parser.parse(raw, raw.length, 0);
        assertEquals(3, packet.paddingBytes());
        assertArrayEquals(new byte[]{9}, packet.payload());

        byte[] bad = rtpPacket(0, 1, 0, 1, false, new byte[]{9, 0, 0, 9});
        bad[0] |= 0x20;
        RtpParseException error = assertThrows(RtpParseException.class,
                () -> parser.parse(bad, bad.length, 0));
        assertEquals(RtpRejectReason.INVALID_PADDING, error.getReason());
    }
}
