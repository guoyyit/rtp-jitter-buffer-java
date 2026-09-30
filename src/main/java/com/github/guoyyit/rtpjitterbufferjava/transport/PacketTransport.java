package com.github.guoyyit.rtpjitterbufferjava.transport;

import java.io.IOException;
import java.net.SocketAddress;

/**
 * Abstraction over loopback UDP or in-memory pipe transports.
 */
public interface PacketTransport extends AutoCloseable {

    void send(byte[] data, int length) throws IOException;

    PacketDatagram receive() throws IOException;

    SocketAddress localAddress();

    SocketAddress remoteAddress();

    void close() throws IOException;
}
