package com.github.guoyyit.rtpjitterbufferjava.session;

import com.github.guoyyit.rtpjitterbufferjava.buffer.AdmissionOutcome;
import com.github.guoyyit.rtpjitterbufferjava.engine.GlobalBufferLimiter;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.NtpTime;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.ReportBlock;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpMessage;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacketParser;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpReporter;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpSr;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.SenderInfo;
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
    private final RtcpPacketParser rtcpParser = new RtcpPacketParser();
    private final GlobalBufferLimiter globalLimiter;
    private final ConcurrentHashMap<Long, SsrcState> ssrcs = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SsrcConflictEvent> conflictEvents =
            new CopyOnWriteArrayList<>();
    private final ReceiveCounters sessionCounters = new ReceiveCounters();
    private final AtomicLong rtcpReceivedPackets = new AtomicLong();
    private final AtomicLong rtcpRejectedPackets = new AtomicLong();
    private final ConcurrentHashMap<Long, SrReception> senderReports =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ReceiveCounters> reportBaselines =
            new ConcurrentHashMap<>();

    private volatile boolean terminated;
    private volatile long currentLocalSsrc;
    private volatile RtcpReporter reporter;
    private final LongSupplier nanoClock;
    private final long nanoAnchor;
    private final long ntpAnchor;

    public RtpSession(String id, RtpSessionConfig config) {
        this(id, config, GlobalBufferLimiter.UNLIMITED);
    }

    public RtpSession(String id, RtpSessionConfig config, GlobalBufferLimiter globalLimiter) {
        this(id, config, globalLimiter, System::nanoTime);
    }

    /**
     * Full constructor allowing a custom nanosecond clock. The clock drives
     * the NTP conversion used for RTCP LSR/DLSR bookkeeping, which makes
     * controlled round-trip experiments deterministic.
     */
    public RtpSession(String id, RtpSessionConfig config, GlobalBufferLimiter globalLimiter,
                      LongSupplier nanoClock) {
        this.id = id;
        this.config = config;
        this.parser = new RtpPacketParser(config.payloadTypes());
        this.globalLimiter = globalLimiter;
        this.currentLocalSsrc = config.localSsrc() & 0xFFFFFFFFL;
        this.nanoClock = nanoClock;
        this.nanoAnchor = nanoClock.getAsLong();
        this.ntpAnchor = NtpTime.now();
    }

    /** Converts a nanoClock timestamp into the NTP domain via the anchor. */
    private long toNtp(long nanos) {
        return NtpTime.addNanos(ntpAnchor, nanos - nanoAnchor);
    }

    /** Current NTP timestamp on this session's clock (RTCP bookkeeping). */
    public long currentNtp() {
        return toNtp(nanoClock.getAsLong());
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
        if (RtcpPacketParser.isRtcp(raw, length)) {
            handleRtcp(raw, length, arrivalNanos);
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

    /** Well-formed RTCP datagrams handled on this session. */
    public long rtcpReceivedPackets() {
        return rtcpReceivedPackets.get();
    }

    /** Malformed or truncated RTCP datagrams rejected by this session. */
    public long rtcpRejectedPackets() {
        return rtcpRejectedPackets.get();
    }

    /**
     * Handles one inbound RTCP datagram. Invalid RTCP is counted and dropped
     * without disturbing the media path or subsequent datagrams.
     */
    private void handleRtcp(byte[] raw, int length, long arrivalNanos) {
        List<RtcpMessage> messages;
        try {
            messages = rtcpParser.parse(raw, length);
        } catch (RuntimeException error) {
            rtcpRejectedPackets.incrementAndGet();
            return;
        }
        rtcpReceivedPackets.incrementAndGet();
        for (RtcpMessage message : messages) {
            onRtcpMessage(message, arrivalNanos);
        }
    }

    private void onRtcpMessage(RtcpMessage message, long arrivalNanos) {
        if (message instanceof RtcpSr sr) {
            // Remember the sender's NTP timestamp so the next RR can carry
            // LSR/DLSR for round-trip time measurement (RFC 3550 6.4.1).
            senderReports.put(sr.senderSsrc(), new SrReception(
                    NtpTime.middle32(sr.senderInfo().ntpTimestamp()), toNtp(arrivalNanos)));
        }
    }

    /**
     * Starts periodic compound RTCP reporting to the given sink. The report
     * cadence follows {@code rtp.rtcp-interval}; reporting runs on its own
     * daemon thread and never blocks media handling.
     */
    public synchronized void startRtcpReporting(RtcpReporter.RtcpSink sink) {
        if (terminated || reporter != null) {
            return;
        }
        reporter = new RtcpReporter(new SessionReportSource(), sink, config.rtcpInterval());
        reporter.start();
    }

    /** Stops periodic reporting and releases the scheduler thread. */
    public synchronized void stopRtcpReporting() {
        if (reporter != null) {
            reporter.stop();
            reporter = null;
        }
    }

    /** Whether periodic RTCP reporting is currently running. */
    public boolean rtcpReporting() {
        RtcpReporter current = reporter;
        return current != null && current.running();
    }

    /** Builds the compound report this session would emit right now. */
    public byte[] buildRtcpReport() {
        return new RtcpReporter(new SessionReportSource(), data -> { },
                config.rtcpInterval()).buildCurrentReport();
    }

    private ReportBlock buildReportBlock(SsrcState state) {
        ReceiveCounters counters = state.counters();
        ReceiveCounters baseline = reportBaselines.get(state.ssrc());
        long fraction = 0;
        if (baseline != null) {
            fraction = Math.round(counters.fractionLostSince(baseline) * 256.0);
            fraction = Math.min(255, Math.max(0, fraction));
        }
        reportBaselines.put(state.ssrc(), counters.snapshot());

        long jitterTimestampUnits =
                state.jitterBuffer().jitterNanos() * config.clockRate() / 1_000_000_000L;
        SrReception lastSr = senderReports.get(state.ssrc());
        long lsr = lastSr == null ? 0 : lastSr.lsrMiddle32;
        long dlsr = lastSr == null ? 0
                : RtcpReporter.dlsrUnits(lastSr.receivedNtp, toNtp(nanoClock.getAsLong()));
        return new ReportBlock(state.ssrc(), (int) fraction, counters.cumulativeLost(),
                counters.extendedHighestSequence(), jitterTimestampUnits, lsr, dlsr);
    }

    /** Middle 32 bits of the last received SR plus its local arrival time. */
    private static final class SrReception {
        private final long lsrMiddle32;
        private final long receivedNtp;

        private SrReception(long lsrMiddle32, long receivedNtp) {
            this.lsrMiddle32 = lsrMiddle32;
            this.receivedNtp = receivedNtp;
        }
    }

    /** Feeds live session statistics into the periodic RTCP reporter. */
    private final class SessionReportSource implements RtcpReporter.ReportSource {
        private static final int MAX_REPORT_BLOCKS = 31; // 5-bit RC field

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
            List<ReportBlock> blocks = new ArrayList<>();
            for (SsrcState state : ssrcs.values()) {
                if (blocks.size() >= MAX_REPORT_BLOCKS) {
                    break;
                }
                blocks.add(buildReportBlock(state));
            }
            return blocks;
        }

        @Override
        public SenderInfo senderInfo() {
            // This endpoint only receives media, so it emits receiver reports.
            return null;
        }
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
