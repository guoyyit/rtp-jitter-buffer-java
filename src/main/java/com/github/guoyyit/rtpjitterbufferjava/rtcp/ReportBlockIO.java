package com.github.guoyyit.rtpjitterbufferjava.rtcp;

import java.nio.ByteBuffer;

final class ReportBlockIO {

    static final int BLOCK_LENGTH = 24;

    private ReportBlockIO() {
    }

    static void writeHeader(ByteBuffer buffer, int packetType, int reportCount, int byteLength,
                            long ssrc) {
        int lengthInWordsMinusOne = byteLength / 4 - 1;
        buffer.put((byte) (0x80 | (reportCount & 0x1F)));
        buffer.put((byte) packetType);
        buffer.putShort((short) lengthInWordsMinusOne);
        buffer.putInt((int) ssrc);
    }

    static void writeBlock(ByteBuffer buffer, ReportBlock block) {
        buffer.putInt((int) block.ssrc());
        int lost24 = (int) Math.min(0xFFFFFFL, Math.max(0, block.cumulativeLost()));
        buffer.put((byte) (block.fractionLost() & 0xFF));
        buffer.put((byte) ((lost24 >> 16) & 0xFF));
        buffer.put((byte) ((lost24 >> 8) & 0xFF));
        buffer.put((byte) (lost24 & 0xFF));
        buffer.putInt((int) block.extendedHighestSequence());
        buffer.putInt((int) block.jitter());
        buffer.putInt((int) block.lastSenderReport());
        buffer.putInt((int) block.delaySinceLastSenderReport());
    }
}
