package com.github.guoyyit.rtpjitterbufferjava.session;

import com.github.guoyyit.rtpjitterbufferjava.buffer.AdmissionOutcome;
import com.github.guoyyit.rtpjitterbufferjava.engine.GlobalBufferLimiter;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.NtpTime;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RemoteSourceReport;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.ReportBlock;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpMessage;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacketParser;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpReporter;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpSr;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPacket;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPacketParser;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpParseException;
import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpRejectReason;
import com.github.guoyyit.rtpjitterbufferjava.stats.ReceiveCounters;
import com.github.guoyyit.rtpjitterbufferjava.transport.PacketTransport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

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
    private final ConcurrentHashMap<Long, RemoteSourceReport> remoteReports =
            new ConcurrentHashMap<>();
    private final RtcpPacketParser rtcpParser = new RtcpPacketParser();
    private final AtomicLong rtcpRejected = new AtomicLong();

    private volatile boolean terminated;
    private volatile long currentLocalSsrc;
    private volatile Duration rtcpInterval;
    private volatile RtcpReporter.RtcpSink rtcpSink;
    private volatile RtcpReporter rtcpReporter;
    private final LongSupplier ntpClock;

    public RtpSession(String id, RtpSessionConfig config) {
        this(id, config, GlobalBufferLimiter.UNLIMITED);
    }

    public RtpSession(String id, RtpSessionConfig config, GlobalBufferLimiter globalLimiter) {
        this(id, config, globalLimiter, NtpTime::now);
    }

    public RtpSession(String id, RtpSessionConfig config, GlobalBufferLimiter globalLimiter,
                      LongSupplier ntpClock) {
        this.id = id;
        this.config = config;
        this.parser = new RtpPacketParser(config.payloadTypes());
        this.globalLimiter = globalLimiter;
        this.currentLocalSsrc = config.localSsrc() & 0xFFFFFFFFL;
        this.rtcpInterval = config.rtcpInterval();
        this.ntpClock = ntpClock;
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
        if (isRtcp(raw, length)) {
            receiveRtcp(raw, length, arrivalNanos);
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
        stopRtcpReporting();
        for (SsrcState state : ssrcs.values()) {
            int buffered = state.jitterBuffer().size();
            state.jitterBuffer().flush();
            for (int i = 0; i < buffered; i++) {
                globalLimiter.release();
            }
        }
        ssrcs.clear();
        remoteReports.clear();
    }

    // ------------------------------------------------------------------
    // RTCP
    // ------------------------------------------------------------------

    /**
     * Distinguishes RTCP from RTP by payload type: RTCP PTs occupy 200..204
     * (RFC 5761 4), well above the RTP range (0..127).
     */
    private static boolean isRtcp(byte[] raw, int length) {
        if (raw == null || length < 2 || raw.length < length) {
            return false;
        }
        int payloadType = raw[1] & 0xFF;
        return payloadType >= RtcpMessage.PT_SR && payloadType <= RtcpMessage.PT_APP;
    }

    private void receiveRtcp(byte[] raw, int length, long arrivalNanos) {
        List<RtcpMessage> messages;
        try {
            messages = rtcpParser.parse(raw, length);
        } catch (RuntimeException error) {
            // Malformed/truncated RTCP is rejected independently of RTP stats and
            // must not disturb processing of later valid packets.
            rtcpRejected.incrementAndGet();
            return;
        }
        for (RtcpMessage message : messages) {
            if (message instanceof RtcpSr sr) {
                remoteReports
                        .computeIfAbsent(sr.senderSsrc(), RemoteSourceReport::new)
                        .onSenderReport(sr.senderInfo().ntpTimestamp(), ntpClock.getAsLong());
            }
        }
    }

    /** Number of malformed/truncated RTCP datagrams explicitly rejected. */
    public long rejectedRtcpPackets() {
        return rtcpRejected.get();
    }

    /**
     * Starts periodic compound RTCP reporting toward the given sink on the
     * configured {@code rtp.rtcp-interval}. Runs on a dedicated daemon thread.
     */
    public synchronized void startRtcpReporting(RtcpReporter.RtcpSink sink) {
        if (rtcpReporter != null) {
            return;
        }
        this.rtcpSink = sink;
        RtcpReporter reporter = new RtcpReporter(new SessionReportSink(), sink, rtcpInterval);
        reporter.start();
        rtcpReporter = reporter;
    }

    /** Convenience overload reporting over a {@link PacketTransport}. */
    public void startRtcpReporting(PacketTransport transport) {
        startRtcpReporting(data -> transport.send(data, data.length));
    }

    /** Stops periodic reporting and releases its scheduler thread. */
    public synchronized void stopRtcpReporting() {
        if (rtcpReporter != null) {
            rtcpReporter.stop();
            rtcpReporter = null;
        }
        rtcpSink = null;
    }

    /** Adjusts the reporting period; reschedules the running task immediately. */
    public synchronized void setRtcpInterval(Duration interval) {
        this.rtcpInterval = interval;
        if (rtcpReporter != null) {
            rtcpReporter.stop();
            RtcpReporter reporter = new RtcpReporter(new SessionReportSink(), rtcpSink, interval);
            reporter.start();
            rtcpReporter = reporter;
        }
    }

    public Duration rtcpInterval() {
        return rtcpInterval;
    }

    public boolean rtcpReportingRunning() {
        RtcpReporter reporter = rtcpReporter;
        return reporter != null && reporter.running();
    }

    /** Builds the current compound RTCP report without scheduling (tests). */
    public byte[] buildCurrentRtcpReport() {
        return buildCurrentRtcpReportAt(ntpClock.getAsLong());
    }

    /** Builds a compound RR at the supplied local wall-clock NTP time (tests). */
    public byte[] buildCurrentRtcpReportAt(long nowNtp) {
        SessionReportSink source = new SessionReportSink();
        List<ReportBlock> blocks = buildReportBlocks(nowNtp);
        RtcpReporter reporter = new RtcpReporter(source, data -> { }, rtcpInterval);
        byte[] compound = reporter.buildReceiverReport(currentLocalSsrc, config.cname(), blocks);
        // A test build is an observed report like a successful send.
        source.onReportSent();
        return compound;
    }

    private List<ReportBlock> buildReportBlocks(long nowNtp) {
        List<ReportBlock> blocks = new ArrayList<>();
        Set<Long> reported = new TreeSet<>();
        reported.addAll(ssrcs.keySet());
        reported.addAll(remoteReports.keySet());
        for (long ssrc : reported) {
            SsrcState state = ssrcs.get(ssrc);
            ReceiveCounters counters = state == null
                    ? new ReceiveCounters() : state.counters();
            RemoteSourceReport remote = remoteReports.get(ssrc);
            long lsr = remote == null ? 0L : remote.lastSr();
            long dlsr = remote == null ? 0L : remote.delaySinceLastSrUnitsByNtp(nowNtp);
            blocks.add(new ReportBlock(
                    ssrc,
                    counters.fractionLostOctetSinceBaseline(),
                    counters.cumulativeLost(),
                    counters.extendedHighestSequence(),
                    state == null ? 0L : state.interarrivalJitterClockUnits(),
                    lsr,
                    dlsr));
        }
        return blocks;
    }

    private void markReportBaselines() {
        for (SsrcState state : ssrcs.values()) {
            state.counters().markReportBaseline();
        }
    }

    private final class SessionReportSink implements RtcpReporter.ReportSource {
        @Override
        public long localSsrc() {
            return currentLocalSsrc;
        }

        @Override
        public String cname() {
            return config.cname();
        }

        @Override
        public List<ReportBlock> reportBlocks() {
            return buildReportBlocks(ntpClock.getAsLong());
        }

        @Override
        public com.github.guoyyit.rtpjitterbufferjava.rtcp.SenderInfo senderInfo() {
            return null;
        }

        @Override
        public void onReportSent() {
            markReportBaselines();
        }
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
