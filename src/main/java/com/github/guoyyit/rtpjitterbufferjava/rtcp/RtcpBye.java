package com.github.guoyyit.rtpjitterbufferjava.rtcp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class RtcpBye extends RtcpPacket {

    public static final int PT = 203;

    private final long[] ssrcs;
    private final String reason;

    public RtcpBye(long[] ssrcs, String reason) {
        this.ssrcs = Arrays.copyOf(ssrcs, ssrcs.length);
        this.reason = reason == null ? "" : reason;
    }

    public long[] ssrcs() {
        return Arrays.copyOf(ssrcs, ssrcs.length);
    }

    public String reason() {
        return reason;
    }

    @Override
    public int packetType() {
        return PT;
    }

    @Override
    public int byteLength() {
        int length = 4 + ssrcs.length * 4;
        if (!reason.isEmpty()) {
            int reasonBytes = reason.getBytes(StandardCharsets.UTF_8).length;
            length += 1 + reasonBytes;
            length = (length + 3) & ~3;
        }
        return length;
    }

    @Override
    public void writeTo(ByteBuffer buffer) {
        buffer.put((byte) (0x80 | (ssrcs.length & 0x1F)));
        buffer.put((byte) PT);
        buffer.putShort((short) (byteLength() / 4 - 1));
        for (long ssrc : ssrcs) {
            buffer.putInt((int) ssrc);
        }
        if (!reason.isEmpty()) {
            byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
            buffer.put((byte) reasonBytes.length);
            buffer.put(reasonBytes);
            int padding = (4 - ((1 + reasonBytes.length) & 3)) & 3;
            for (int i = 0; i < padding; i++) {
                buffer.put((byte) 0);
            }
        }
    }
}
