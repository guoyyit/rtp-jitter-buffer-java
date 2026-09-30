package com.github.guoyyit.rtpjitterbufferjava.transport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * UDP transport bound to the local loopback interface. RTP and RTCP use two
 * separate sockets (RTCP on rtpPort + 1 as recommended by RFC 3550/3551).
 * Reception happens on dedicated daemon threads so network I/O never blocks
 * media processing.
 */
public class UdpLoopbackTransport implements PacketTransport {

    private static final int MAX_PACKET_SIZE = 2048;

    private final int rtpPort;
    private final int rtcpPort;

    private DatagramSocket rtpSocket;
    private DatagramSocket rtcpSocket;
    private Thread rtpThread;
    private Thread rtcpThread;
    private volatile boolean open;

    public UdpLoopbackTransport(int rtpPort, int rtcpPort) {
        this.rtpPort = rtpPort;
        this.rtcpPort = rtcpPort;
    }

    @Override
    public void start(TransportEndpoint rtpHandler, TransportEndpoint rtcpHandler) throws IOException {
        rtpSocket = new DatagramSocket(new InetSocketAddress("127.0.0.1", rtpPort));
        rtcpSocket = new DatagramSocket(new InetSocketAddress("127.0.0.1", rtcpPort));
        open = true;
        rtpThread = receiveLoop(rtpSocket, rtpHandler, "rtp-" + rtpPort);
        rtcpThread = receiveLoop(rtcpSocket, rtcpHandler, "rtcp-" + rtcpPort);
    }

    private Thread receiveLoop(DatagramSocket socket, TransportEndpoint handler, String name) {
        Thread thread = new Thread(() -> {
            while (open) {
                try {
                    byte[] buffer = new byte[MAX_PACKET_SIZE];
                    DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                    socket.receive(datagram);
                    handler.onPacket(buffer, 0, datagram.getLength(), datagram.getSocketAddress());
                } catch (IOException e) {
                    if (open) {
                        open = false;
                    }
                    break;
                } catch (RuntimeException e) {
                    // A handler failure must not kill the receiver thread.
                }
            }
        }, "rtp-jitter-" + name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @Override
    public void sendRtp(byte[] data, SocketAddress target) throws IOException {
        send(rtpSocket, data, target);
    }

    @Override
    public void sendRtcp(byte[] data, SocketAddress target) throws IOException {
        send(rtcpSocket, data, target);
    }

    private void send(DatagramSocket socket, byte[] data, SocketAddress target) throws IOException {
        if (socket == null || !open) {
            throw new IOException("transport is not open");
        }
        socket.send(new DatagramPacket(data, data.length, target));
    }

    @Override
    public void close() {
        open = false;
        closeQuietly(rtpSocket);
        closeQuietly(rtcpSocket);
        interruptQuietly(rtpThread);
        interruptQuietly(rtcpThread);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    private static void closeQuietly(DatagramSocket socket) {
        if (socket != null) {
            socket.close();
        }
    }

    private static void interruptQuietly(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }
}
