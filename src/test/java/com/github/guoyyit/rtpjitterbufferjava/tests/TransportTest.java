package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.transport.InMemoryTransport;
import com.github.guoyyit.rtpjitterbufferjava.transport.PacketDatagram;
import com.github.guoyyit.rtpjitterbufferjava.transport.UdpLoopbackTransport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TransportTest {

    @Test
    void inMemoryPairDeliversDatagrams() throws Exception {
        InMemoryTransport a = InMemoryTransport.pair(64);
        InMemoryTransport b = a.peer();
        byte[] payload = {1, 2, 3, 4};
        a.send(payload, payload.length);
        PacketDatagram received = b.receive(1, TimeUnit.SECONDS);
        assertNotNull(received);
        assertArrayEquals(payload, received.data());
    }

    @Test
    void udpLoopbackRoundTrip() throws Exception {
        int port = 49152 + (int) (Math.random() * 1000);
        UdpLoopbackTransport server = new UdpLoopbackTransport(port, "127.0.0.1", port);
        try (UdpLoopbackTransport client = new UdpLoopbackTransport(0, "127.0.0.1", port)) {
            byte[] payload = {9, 8, 7};
            client.send(payload, payload.length);
            PacketDatagram datagram = server.receive();
            assertArrayEquals(payload, datagram.data());
        } finally {
            server.close();
        }
    }
}
