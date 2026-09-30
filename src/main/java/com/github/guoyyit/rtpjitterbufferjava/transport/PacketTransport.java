package com.github.guoyyit.rtpjitterbufferjava.transport;

import java.io.IOException;
import java.net.SocketAddress;

public interface PacketTransport {

    void start(TransportEndpoint rtpHandler, TransportEndpoint rtcpHandler) throws IOException;

    void sendRtp(byte[] data, SocketAddress target) throws IOException;

    void sendRtcp(byte[] data, SocketAddress target) throws IOException;

    void close();

    boolean isOpen();
}
