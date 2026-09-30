package com.github.guoyyit.rtpjitterbufferjava.engine;

import com.github.guoyyit.rtpjitterbufferjava.session.RtpSession;
import com.github.guoyyit.rtpjitterbufferjava.session.RtpSessionConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages concurrent, resource-isolated RTP sessions and the global buffer cap.
 */
public class RtpReceiver {

    private static final int DEFAULT_GLOBAL_LIMIT = 16_384;

    private final ConcurrentHashMap<String, RtpSession> sessions = new ConcurrentHashMap<>();
    private final SharedBufferLimiter globalLimiter;

    public RtpReceiver() {
        this(DEFAULT_GLOBAL_LIMIT);
    }

    public RtpReceiver(int globalMaxBufferedPackets) {
        this.globalLimiter = new SharedBufferLimiter(globalMaxBufferedPackets);
    }

    public RtpSession createSession(String id, RtpSessionConfig config) {
        RtpSession session = new RtpSession(id, config, globalLimiter);
        RtpSession previous = sessions.putIfAbsent(id, session);
        if (previous != null) {
            return previous;
        }
        return session;
    }

    public Optional<RtpSession> getSession(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public List<RtpSession> sessions() {
        return new ArrayList<>(sessions.values());
    }

    public int totalBufferedPackets() {
        return globalLimiter.currentUsage();
    }

    public int globalLimit() {
        return globalLimiter.limit;
    }

    public void close() {
        for (RtpSession session : sessions.values()) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // One failing session must not prevent shutdown of the others.
            }
        }
        sessions.clear();
    }

    private static final class SharedBufferLimiter implements GlobalBufferLimiter {
        private final int limit;
        private final AtomicInteger usage = new AtomicInteger();

        private SharedBufferLimiter(int limit) {
            this.limit = limit;
        }

        @Override
        public boolean tryAcquire() {
            while (true) {
                int current = usage.get();
                if (current >= limit) {
                    return false;
                }
                if (usage.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }

        @Override
        public void release() {
            usage.updateAndGet(value -> Math.max(0, value - 1));
        }

        @Override
        public int currentUsage() {
            return usage.get();
        }
    }
}
