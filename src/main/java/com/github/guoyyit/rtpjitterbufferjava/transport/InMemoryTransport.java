package com.github.guoyyit.rtpjitterbufferjava.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Paired in-memory pipe transport: every send arrives at the peer's queue.
 * The queue is bounded so a slow consumer applies back-pressure instead of
 * growing without limit.
 */
public class InMemoryTransport implements PacketTransport {

    private final String name;
    private final LinkedBlockingQueue<PacketDatagram> inbox;
    private InMemoryTransport peer;
    private final InetSocketAddress localAddress;
    private final InetSocketAddress remoteAddress;
    private volatile boolean closed;

    private InMemoryTransport(String name,
                              LinkedBlockingQueue<PacketDatagram> inbox,
                              InMemoryTransport peer,
                              InetSocketAddress localAddress,
                              InetSocketAddress remoteAddress) {
        this.name = name;
        this.inbox = inbox;
        this.peer = peer;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
    }

    /** Creates a connected pair; anything sent on one is received on the other. */
    public static InMemoryTransport pair(int capacityBytes) {
        int capacity = Math.max(1, capacityBytes);
        LinkedBlockingQueue<PacketDatagram> aInbox = new LinkedBlockingQueue<>(capacity);
        LinkedBlockingQueue<PacketDatagram> bInbox = new LinkedBlockingQueue<>(capacity);
        InetSocketAddress addressA = new InetSocketAddress("127.0.0.1", 10000);
        InetSocketAddress addressB = new InetSocketAddress("127.0.0.1", 10001);
        InMemoryTransport a = new InMemoryTransport(
                "a", aInbox, null, addressA, addressB);
        InMemoryTransport b = new InMemoryTransport(
                "b", bInbox, a, addressB, addressA);
        a.peer = b;
        return a;
    }

    public InMemoryTransport peer() {
        return peer;
    }

    public PacketDatagram receive(long timeout, TimeUnit unit) throws IOException {
        if (closed) {
            throw new IOException(name + " transport closed");
        }
        try {
            return inbox.poll(timeout, unit);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for datagram", error);
        }
    }

    @Override
    public void send(byte[] data, int length) throws IOException {
        if (closed || peer == null || peer.closed) {
            throw new IOException("cannot send on closed transport pair");
        }
        if (length > data.length) {
            throw new IllegalArgumentException("length exceeds data");
        }
        byte[] copy = new byte[length];
        System.arraycopy(data, 0, copy, 0, length);
        peer.inbox.offer(new PacketDatagram(copy, length, System.nanoTime()));
    }

    @Override
    public PacketDatagram receive() throws IOException {
        return receive(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
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
        closed = true;
    }
}
