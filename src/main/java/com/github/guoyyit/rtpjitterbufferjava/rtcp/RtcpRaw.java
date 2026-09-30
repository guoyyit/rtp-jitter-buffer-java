package com.github.guoyyit.rtpjitterbufferjava.rtcp;

import java.nio.ByteBuffer;

/**
 * RTCP packet types that this implementation can forward but does not model
 * (for example SDES) are preserved as opaque compound chunks.
 */
public final class RtcpRaw extends RtcpPacket {

    private final int packetType;
    private final byte[] data;

    public RtcpRaw(int packetType, byte[] data) {
        this.packetType = packetType;
        this.data = data.clone();
    }

    @Override
    public int packetType() {
        return packetType;
    }

    @Override
    public int byteLength() {
        return data.length;
    }

    @Override
    public void writeTo(ByteBuffer buffer) {
        buffer.put(data);
    }
}
