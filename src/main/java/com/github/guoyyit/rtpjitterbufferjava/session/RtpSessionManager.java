package com.github.guoyyit.rtpjitterbufferjava.session;

import com.github.guoyyit.rtpjitterbufferjava.buffer.GlobalBufferBudget;
import com.github.guoyyit.rtpjitterbufferjava.clock.Clock;
import com.github.guoyyit.rtpjitterbufferjava.codec.PayloadTypeRegistry;
import com.github.guoyyit.rtpjitterbufferjava.config.BufferProperties;
import com.github.guoyyit.rtpjitterbufferjava.transport.PacketTransport;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RtpSessionManager {

    private final BufferProperties properties;
    private final PayloadTypeRegistry payloadTypeRegistry;
    private final GlobalBufferBudget globalBudget;
    private final Clock clock;

    private final Map<String, RtpSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, PacketTransport> transports = new ConcurrentHashMap<>();

    public RtpSessionManager(BufferProperties properties, PayloadTypeRegistry payloadTypeRegistry,
                             GlobalBufferBudget globalBudget, Clock clock) {
        this.properties = properties;
        this.payloadTypeRegistry = payloadTypeRegistry;
        this.globalBudget = globalBudget;
        this.clock = clock;
    }

    public RtpSession createSession(String id, long localSsrc) {
        RtpSession session = new RtpSession(id, localSsrc, properties, payloadTypeRegistry,
                globalBudget, clock);
        RtpSession existing = sessions.putIfAbsent(id, session);
        if (existing != null) {
            throw new IllegalStateException("session already exists: " + id);
        }
        return session;
    }

    public RtpSession getSession(String id) {
        return sessions.get(id);
    }

    /**
     * Binds a transport to a session and starts reading from it. Datagrams are
     * dispatched to the owning session only; exceptions inside one session are
     * contained by {@link RtpSession} and cannot affect other sessions.
     */
    public void bindTransport(String id, PacketTransport transport) {
        RtpSession session = requireSession(id);
        transports.put(id, transport);
        try {
            transport.start(
                    (data, offset, length, source) -> routeRtp(id, data, offset, length, source),
                    (data, offset, length, source) -> routeRtcp(id, data, offset, length, source));
        } catch (IOException e) {
            transports.remove(id);
            throw new IllegalStateException("failed to start transport for session " + id, e);
        }
    }

    public void routeRtp(String id, byte[] data, int offset, int length, SocketAddress source) {
        RtpSession session = sessions.get(id);
        if (session != null && !session.isClosed()) {
            session.onRtp(data, offset, length, source);
        }
    }

    public void routeRtcp(String id, byte[] data, int offset, int length, SocketAddress source) {
        RtpSession session = sessions.get(id);
        if (session != null && !session.isClosed()) {
            session.onRtcp(data, offset, length, source);
        }
    }

    public Collection<RtpSession> getSessions() {
        return sessions.values();
    }

    public int totalBufferedPackets() {
        int total = 0;
        for (RtpSession session : sessions.values()) {
            total += session.bufferedPackets();
        }
        return total;
    }

    public void closeAll() {
        for (PacketTransport transport : transports.values()) {
            transport.close();
        }
        transports.clear();
        for (RtpSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }

    private RtpSession requireSession(String id) {
        RtpSession session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("unknown session: " + id);
        }
        return session;
    }
}
