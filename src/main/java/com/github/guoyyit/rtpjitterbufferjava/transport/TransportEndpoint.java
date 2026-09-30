package com.github.guoyyit.rtpjitterbufferjava.transport;

import java.net.SocketAddress;

@FunctionalInterface
public interface TransportEndpoint {

    void onPacket(byte[] data, int offset, int length, SocketAddress source);
}
