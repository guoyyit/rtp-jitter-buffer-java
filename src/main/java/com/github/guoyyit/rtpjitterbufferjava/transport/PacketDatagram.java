package com.github.guoyyit.rtpjitterbufferjava.transport;

/**
 * One raw datagram plus its transport capture timestamp.
 */
public record PacketDatagram(byte[] data, int length, long arrivalNanos) {
}
