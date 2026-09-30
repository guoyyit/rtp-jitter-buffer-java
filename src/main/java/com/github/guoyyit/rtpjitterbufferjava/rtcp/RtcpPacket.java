package com.github.guoyyit.rtpjitterbufferjava.rtcp;

import java.nio.ByteBuffer;

public abstract class RtcpPacket {

    public abstract int packetType();

    public abstract int byteLength();

    public abstract void writeTo(ByteBuffer buffer);

    public final byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(byteLength());
        writeTo(buffer);
        return buffer.array();
    }
}
