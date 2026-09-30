package com.github.guoyyit.rtpjitterbufferjava.transport;

import java.io.IOException;
import java.net.SocketAddress;

/**
 * In-process datagram transport used by tests and in-memory simulations.
 * Two transports can be linked as a duplex pair; sent packets are delivered
 * immediately on the sender thread, mimicking loopback without real sockets.
 */
public class InMemoryTransport implements PacketTransport {

    private final String name;
    private volatile TransportEndpoint rtpHandler;
    private volatile TransportEndpoint rtcpHandler;
    private volatile InMemoryTransport rtpPeer;
    private volatile InMemoryTransport rtcpPeer;
    private volatile SocketAddress localAddress;
    private volatile SocketAddress peerAddress;
    private volatile boolean open;

    public InMemoryTransport(String name) {
        this.name = name;
    }

    /**
     * Links two transports as a full-duplex pair (RTP and RTCP each cross-linked).
     */
    public static void pair(InMemoryTransport a, InMemoryTransport b,
                            SocketAddress aAddress, SocketAddress bAddress) {
        a.rtpPeer = b;
        a.rtcpPeer = b;
        a.localAddress = aAddress;
        a.peerAddress = bAddress;
        b.rtpPeer = a;
        b.rtcpPeer = a;
        b.localAddress = bAddress;
        b.peerAddress = aAddress;
    }

    public void deliverRtp(byte[] data) {
        deliverRtp(data.clone(), 0, data.length);
    }

    public void deliverRtcp(byte[] data) {
        deliverRtcp(data.clone(), 0, data.length);
    }

    public void deliverRtp(byte[] data, int offset, int length) {
        TransportEndpoint handler = rtpHandler;
        if (handler != null && open) {
            handler.onPacket(data.clone(), offset, length, peerAddress);
        }
    }

    public void deliverRtcp(byte[] data, int offset, int length) {
        TransportEndpoint handler = rtcpHandler;
        if (handler != null && open) {
            handler.onPacket(data.clone(), offset, length, peerAddress);
        }
    }

    @Override
    public void start(TransportEndpoint rtpHandler, TransportEndpoint rtcpHandler) {
        this.rtpHandler = rtpHandler;
        this.rtcpHandler = rtcpHandler;
        this.open = true;
    }

    @Override
    public void sendRtp(byte[] data, SocketAddress target) throws IOException {
        InMemoryTransport peer = rtpPeer;
        if (peer == null || !open) {
            throw new IOException("transport '" + name + "' is not connected");
        }
        peer.deliverRtp(data, 0, data.length);
    }

    @Override
    public void sendRtcp(byte[] data, SocketAddress target) throws IOException {
        InMemoryTransport peer = rtcpPeer;
        if (peer == null || !open) {
            throw new IOException("transport '" + name + "' is not connected");
        }
        peer.deliverRtcp(data, 0, data.length);
    }

    @Override
    public void close() {
        open = false;
        rtpHandler = null;
        rtcpHandler = null;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    public SocketAddress getLocalAddress() {
        return localAddress;
    }

    public SocketAddress getPeerAddress() {
        return peerAddress;
    }
}
