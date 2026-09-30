package com.github.guoyyit.rtpjitterbufferjava.transport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * DatagramSocket transport bound to the local loopback interface.
 */
public class UdpLoopbackTransport implements PacketTransport {

    private static final int MAX_DATAGRAM = 2048;

    private final DatagramSocket socket;
    private final InetSocketAddress localAddress;
    private final InetSocketAddress remoteAddress;

    public UdpLoopbackTransport(int localPort, String remoteHost, int remotePort) throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        this.socket = new DatagramSocket(new InetSocketAddress(loopback, localPort));
        this.localAddress = (InetSocketAddress) socket.getLocalSocketAddress();
        this.remoteAddress = new InetSocketAddress(
                InetAddress.getByName(remoteHost == null ? "127.0.0.1" : remoteHost),
                remotePort);
    }

    @Override
    public void send(byte[] data, int length) throws IOException {
        socket.send(new DatagramPacket(data, length, remoteAddress));
    }

    @Override
    public PacketDatagram receive() throws IOException {
        byte[] buffer = new byte[MAX_DATAGRAM];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        socket.receive(datagram);
        byte[] exact = new byte[datagram.getLength()];
        System.arraycopy(buffer, 0, exact, 0, datagram.getLength());
        return new PacketDatagram(exact, exact.length, System.nanoTime());
    }

    @Override
    public SocketAddress localAddress() {
        return localAddress;
    }

    @Override
    public SocketAddress remoteAddress() {
        return remoteAddress;
    }

    @Override
    public void close() {
        socket.close();
    }
}
