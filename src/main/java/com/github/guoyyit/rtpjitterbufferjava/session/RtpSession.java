package com.github.guoyyit.rtpjitterbufferjava.session;

import com.github.guoyyit.rtpjitterbufferjava.buffer.AdmissionOutcome;
import com.github.guoyyit.rtpjitterbufferjava.engine.GlobalBufferLimiter;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPacket;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPacketParser;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpParseException;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpRejectReason;
import com.github.guoyyit.rtpjitterbufferjava.stats.ReceiveCounters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One RTP/RTCP session demultiplexing an arbitrary number of remote SSRCs.
 */
public class RtpSession {

    private static final int MAX_CONFLICT_EVENTS = 16;

    private final String id;
    private final RtpSessionConfig config;
    private final RtpPacketParser parser;
    private final GlobalBufferLimiter globalLimiter;
    private final ConcurrentHashMap<Long, SsrcState> ssrcs = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SsrcConflictEvent> conflictEvents =
            new CopyOnWriteArrayList<>();
    private final ReceiveCounters sessionCounters = new ReceiveCounters();

    private volatile boolean terminated;
    private volatile long currentLocalSsrc;

    public RtpSession(String id, RtpSessionConfig config) {
        this(id, config, GlobalBufferLimiter.UNLIMITED);
    }

    public RtpSession(String id, RtpSessionConfig config, GlobalBufferLimiter globalLimiter) {
        this.id = id;
        this.config = config;
        this.parser = new RtpPacketParser(config.payloadTypes());
        this.globalLimiter = globalLimiter;
        this.currentLocalSsrc = config.localSsrc() & 0xFFFFFFFFL;
    }

    public String id() {
        return id;
    }

    /**
     * Parses one inbound datagram and dispatches it to the matching SSRC.
     * Parsing failures are counted on the session and never propagate.
     */
    public void receive(byte[] raw, int length, long arrivalNanos) {
        if (terminated) {
            return;
        }
        RtpPacket packet;
        try {
            packet = parser.parse(raw, length, arrivalNanos);
        } catch (RtpParseException error) {
            sessionCounters.recordRejected(error.getReason());
            return;
        } catch (RuntimeException error) {
            sessionCounters.recordRejected(RtpRejectReason.TRUNCATED_PACKET);
            return;
        }

        long ssrc = packet.header().ssrc();
        if (ssrc == currentLocalSsrc) {
            handleSsrcCollision(ssrc, arrivalNanos);
            if (terminated) {
                return;
            }
        }

        SsrcState state = resolveSsrc(ssrc);
        if (state == null) {
            sessionCounters.recordOverloadDropped();
            return;
        }
        state.onPacket(packet);
        admitToBuffer(state, packet);
    }

    /** Removes and returns every packet ready for playout across all SSRCs. */
    public List<RtpPacket> pollReady(long nowNanos) {
        List<RtpPacket> ready = new ArrayList<>();
        for (SsrcState state : ssrcs.values()) {
            List<RtpPacket> due = state.jitterBuffer().due(nowNanos);
            for (RtpPacket packet : due) {
                globalLimiter.release();
                state.counters().recordPlayed();
                ready.add(packet);
            }
        }
        return ready;
    }

    public Optional<SsrcState> ssrcState(long ssrc) {
        return Optional.ofNullable(ssrcs.get(ssrc));
    }

    public List<SsrcConflictEvent> conflictEvents() {
        return Collections.unmodifiableList(conflictEvents);
    }

    public int totalBufferedPackets() {
        int total = 0;
        for (SsrcState state : ssrcs.values()) {
            total += state.jitterBuffer().size();
        }
        return total;
    }

    public boolean terminated() {
        return terminated;
    }

    public ReceiveCounters counters() {
        return sessionCounters;
    }

    /** Aggregated receive counters across every active remote SSRC. */
    public long aggregateReceivedPackets() {
        long total = 0;
        for (SsrcState state : ssrcs.values()) {
            total += state.counters().receivedPackets();
        }
        return total;
    }

    public long aggregateDuplicatePackets() {
        long total = 0;
        for (SsrcState state : ssrcs.values()) {
            total += state.counters().duplicatePackets();
        }
        return total;
    }

    public long aggregateLateDroppedPackets() {
        long total = 0;
        for (SsrcState state : ssrcs.values()) {
            total += state.counters().lateDroppedPackets();
        }
        return total;
    }

    public long aggregateOverloadDroppedPackets() {
        long total = sessionCounters.overloadDroppedPackets();
        for (SsrcState state : ssrcs.values()) {
            total += state.counters().overloadDroppedPackets();
        }
        return total;
    }

    public long aggregatePlayedPackets() {
        long total = 0;
        for (SsrcState state : ssrcs.values()) {
            total += state.counters().playedPackets();
        }
        return total;
    }

    /** Returns the SSRC in use locally after any RFC 3550 collision switch. */
    public long localSsrc() {
        return currentLocalSsrc;
    }

    public void close() {
        terminated = true;
        for (SsrcState state : ssrcs.values()) {
            int buffered = state.jitterBuffer().size();
            state.jitterBuffer().flush();
            for (int i = 0; i < buffered; i++) {
                globalLimiter.release();
            }
        }
        ssrcs.clear();
    }

    private SsrcState resolveSsrc(long ssrc) {
        SsrcState existing = ssrcs.get(ssrc);
        if (existing != null) {
            return existing;
        }
        if (ssrcs.size() >= config.maxSsrcs()) {
            return null;
        }
        SsrcState created = new SsrcState(ssrc, config.jitterBuffer(), config.clockRate());
        SsrcState previous = ssrcs.putIfAbsent(ssrc, created);
        return previous != null ? previous : created;
    }

    private void admitToBuffer(SsrcState state, RtpPacket packet) {
        if (totalBufferedPackets() >= config.maxPacketsPerSession()) {
            state.counters().recordOverloadDropped();
            sessionCounters.recordOverloadDropped();
            return;
        }
        if (!globalLimiter.tryAcquire()) {
            state.counters().recordOverloadDropped();
            sessionCounters.recordOverloadDropped();
            return;
        }

        AdmissionOutcome outcome = state.jitterBuffer().admit(packet);
        switch (outcome) {
            case ACCEPTED -> {
            }
            case DUPLICATE -> {
                globalLimiter.release();
                state.counters().recordDuplicate();
                sessionCounters.recordDuplicate();
            }
            case LATE, REORDER_WINDOW_EXCEEDED -> {
                globalLimiter.release();
                state.counters().recordLateDropped();
                sessionCounters.recordLateDropped();
            }
            case BUFFER_FULL, GLOBAL_LIMIT -> {
                globalLimiter.release();
                state.counters().recordOverloadDropped();
            }
        }
    }

    private void handleSsrcCollision(long ssrc, long arrivalNanos) {
        if (config.conflictPolicy() == SsrcConflictPolicy.TERMINATE) {
            recordCollision(ssrc,
                    "received own SSRC from a remote source; stream terminated per policy",
                    true, arrivalNanos);
            terminated = true;
            return;
        }
        long replacement = (ssrc + 1) & 0xFFFFFFFFL;
        recordCollision(ssrc,
                "SSRC collision detected; switching local SSRC to " + replacement,
                false, arrivalNanos);
        currentLocalSsrc = replacement;
    }

    private void recordCollision(long ssrc, String reason, boolean terminated, long nanos) {
        conflictEvents.add(new SsrcConflictEvent(ssrc, reason, terminated, nanos));
        while (conflictEvents.size() > MAX_CONFLICT_EVENTS) {
            conflictEvents.remove(0);
        }
    }
}
