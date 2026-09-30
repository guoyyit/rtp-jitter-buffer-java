package com.github.guoyyit.rtpjitterbufferjava.session;

import com.github.guoyyit.rtpjitterbufferjava.buffer.AdaptiveDelayController;
import com.github.guoyyit.rtpjitterbufferjava.buffer.BufferBudget;
import com.github.guoyyit.rtpjitterbufferjava.buffer.BufferedPacket;
import com.github.guoyyit.rtpjitterbufferjava.buffer.DropPolicy;
import com.github.guoyyit.rtpjitterbufferjava.buffer.EnqueueResult;
import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBuffer;
import com.github.guoyyit.rtpjitterbufferjava.clock.Clock;
import com.github.guoyyit.rtpjitterbufferjava.codec.PayloadTypeRegistry;
import com.github.guoyyit.rtpjitterbufferjava.codec.RtpFormatException;
import com.github.guoyyit.rtpjitterbufferjava.codec.RtpParser;
import com.github.guoyyit.rtpjitterbufferjava.config.BufferProperties;
import com.github.guoyyit.rtpjitterbufferjava.model.RtpPacket;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.NtpTime;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpScheduler;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpCodec;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpPacket;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.ReceiverReport;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.ReportBlock;
import com.github.guoyyit.rtpjitterbufferjava.stats.ReceptionStats;
import com.github.guoyyit.rtpjitterbufferjava.stats.SeqStatus;
import com.github.guoyyit.rtpjitterbufferjava.stats.SeqUpdate;
import com.github.guoyyit.rtpjitterbufferjava.stats.SessionCounters;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RtpSession {

    private final String id;
    private final long localSsrc;
    private final BufferProperties properties;
    private final PayloadTypeRegistry payloadTypeRegistry;
    private final BufferBudget globalBudget;
    private final Clock clock;
    private final RtpParser parser;
    private final RtcpCodec rtcpCodec;
    private final SessionCounters counters = new SessionCounters();

    private final Map<Long, SsrcState> ssrcStates = new ConcurrentHashMap<>();
    private final List<SsrcCollisionEvent> collisionEvents = new ArrayList<>();

    private volatile boolean closed;
    private volatile RtcpScheduler rtcpScheduler;

    public RtpSession(String id, long localSsrc, BufferProperties properties,
                      PayloadTypeRegistry payloadTypeRegistry, BufferBudget globalBudget, Clock clock) {
        this.id = id;
        this.localSsrc = localSsrc;
        this.properties = properties;
        this.payloadTypeRegistry = payloadTypeRegistry;
        this.globalBudget = globalBudget;
        this.clock = clock;
        this.parser = new RtpParser(payloadTypeRegistry);
        this.rtcpCodec = new RtcpCodec();
    }

    public String getId() {
        return id;
    }

    /**
     * Handles one inbound RTP datagram. Parse failures and processing errors
     * are recorded as counters and never propagate to the transport thread, so
     * one malformed packet cannot stop a session.
     */
    public void onRtp(byte[] data, int offset, int length, SocketAddress source) {
        if (closed) {
            return;
        }
        RtpPacket packet;
        try {
            packet = parser.parse(data, offset, length);
        } catch (RtpFormatException e) {
            counters.rejected(e.getReason());
            return;
        } catch (RuntimeException e) {
            counters.rejected(com.github.guoyyit.rtpjitterbufferjava.codec.RejectReason.TRUNCATED_HEADER);
            return;
        }

        SsrcState state = resolveSsrc(packet, source);
        if (state == null) {
            return;
        }

        long now = clock.currentTimeMillis();

        SeqUpdate update = state.getStats().peekSequence(packet.sequence());
        BufferedPacket buffered = new BufferedPacket(packet, now, update.extendedSequence());
        EnqueueResult result = state.getBuffer().enqueue(buffered,
                properties.getDropPolicy(), globalBudget);

        if (result == EnqueueResult.DUPLICATE || result == EnqueueResult.LATE_DROPPED
                || result == EnqueueResult.SESSION_FULL || result == EnqueueResult.GLOBAL_FULL) {
            recordEnqueueResult(result);
            state.getStats().notifyBufferDiscard(result, false);
            if (result == EnqueueResult.DUPLICATE) {
                state.getStats().recordNonAccepted(
                        SeqStatus.DUPLICATE);
            }
            return;
        }

        SeqUpdate accepted = state.getStats().updateSequence(packet.sequence());
        if (accepted.status() == SeqStatus.INVALID_JUMP) {
            state.getBuffer().remove(buffered.extendedSequence(), globalBudget);
            counters.invalidSequenceJump();
            return;
        }

        state.getStats().updateJitter(packet.timestamp(), now);
        long jitterMs = state.getStats().getJitter() * 1000L
                / payloadTypeRegistry.clockRate(packet.payloadType());
        state.getDelayController().nextDelay(now, jitterMs);
    }

    /**
     * Begins periodic RTCP reporting over the given transport. Runs on a daemon
     * thread and never blocks RTP reception.
     */
    public void startRtcpSender(com.github.guoyyit.rtpjitterbufferjava.transport.PacketTransport transport,
                               SocketAddress target) {
        if (closed || rtcpScheduler != null) {
            return;
        }
        rtcpScheduler = new RtcpScheduler(id, properties.getRtcpIntervalMs(), this::buildRtcpReport);
        rtcpScheduler.start(transport, target);
    }

    /**
     * Sends one RTCP report immediately (for tests or shutdown) without waiting
     * for the periodic timer.
     */
    public byte[] sendRtcpNow(com.github.guoyyit.rtpjitterbufferjava.transport.PacketTransport transport,
                             SocketAddress target) throws java.io.IOException {
        byte[] report = buildRtcpReport();
        if (transport != null && transport.isOpen()) {
            transport.sendRtcp(report, target);
        }
        return report;
    }

    private SsrcState resolveSsrc(RtpPacket packet, SocketAddress source) {
        long ssrc = packet.ssrc();
        if (ssrc == localSsrc) {
            counters.collision();
            recordCollision(ssrc, source, "received packet carrying this session's own SSRC");
            return null;
        }

        SsrcState state = ssrcStates.get(ssrc);
        if (state == null) {
            state = createSsrcState(ssrc, source, packet.payloadType());
            SsrcState existing = ssrcStates.putIfAbsent(ssrc, state);
            if (existing != null) {
                state = existing;
            }
        }

        if (state.isTerminated()) {
            return null;
        }

        SocketAddress previous = state.getSource();
        if (previous != null && !previous.equals(source)) {
            int count = state.recordCollision();
            counters.collision();
            recordCollision(ssrc, source,
                    "same SSRC observed from a new transport address: " + previous + " -> " + source);
            if (properties.getCollisionPolicy() == CollisionPolicy.TERMINATE
                    || count > properties.getCollisionLimit()) {
                state.terminate();
                evict(ssrc, state);
                return null;
            }
            state.setSource(source);
        }
        return state;
    }

    private SsrcState createSsrcState(long ssrc, SocketAddress source, int payloadType) {
        int clockRate = payloadTypeRegistry.clockRate(payloadType);
        ReceptionStats stats = new ReceptionStats(ssrc, clockRate, clock);
        JitterBuffer buffer = new JitterBuffer(properties.getPerSessionMaxPackets(),
                properties.getMaxReorderWindow());
        AdaptiveDelayController delayController = new AdaptiveDelayController(
                properties.getInitialDelayMs(), properties.getMinDelayMs(),
                properties.getMaxDelayMs(), properties.getJitterSafetyFactor(),
                properties.getDelayRecoveryMsPerSecond());
        return new SsrcState(ssrc, stats, buffer, delayController, source);
    }

    private synchronized void recordCollision(long ssrc, SocketAddress source, String reason) {
        collisionEvents.add(new SsrcCollisionEvent(ssrc, reason, clock.currentTimeMillis()));
    }

    private void evict(long ssrc, SsrcState state) {
        state.getBuffer().clear(globalBudget);
        ssrcStates.remove(ssrc, state);
    }

    private void recordEnqueueResult(EnqueueResult result) {
        switch (result) {
            case DUPLICATE -> counters.duplicate();
            case LATE_DROPPED -> counters.lateDrop();
            case SESSION_FULL -> counters.sessionBufferFullDrop();
            case GLOBAL_FULL -> counters.globalBudgetDrop();
            case ACCEPTED -> { }
        }
    }

    /**
     * Handles an inbound RTCP compound packet. SR sender information is kept
     * per SSRC for RTT calculation; BYE terminates the corresponding source.
     */
    public void onRtcp(byte[] data, int offset, int length, SocketAddress source) {
        if (closed) {
            return;
        }
        List<RtcpPacket> packets;
        try {
            packets = rtcpCodec.parse(data, offset, length);
        } catch (RuntimeException e) {
            counters.malformedRtcp();
            return;
        }
        long arrivalNtp = NtpTime.fromMillis(clock.currentTimeMillis());
        for (RtcpPacket packet : packets) {
            if (packet instanceof com.github.guoyyit.rtpjitterbufferjava.rtcp.SenderReport sr) {
                SsrcState state = ssrcStates.get(sr.ssrc());
                if (state != null) {
                    state.getStats().recordReceivedSenderReport(sr.ntpTimestamp(), arrivalNtp);
                }
            } else if (packet instanceof com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpBye bye) {
                for (long ssrc : bye.ssrcs()) {
                    SsrcState state = ssrcStates.remove(ssrc);
                    if (state != null) {
                        evict(ssrc, state);
                    }
                }
            }
        }
    }

    /**
     * Drains every packet whose adaptive playout deadline has passed, across all
     * SSRCs multiplexed in this session.
     */
    public List<BufferedPacket> pollPlayable(long maxCount) {
        List<BufferedPacket> playable = new ArrayList<>();
        long now = clock.currentTimeMillis();
        for (SsrcState state : ssrcStates.values()) {
            if (state.isTerminated()) {
                continue;
            }
            long delayMs = state.getDelayController().currentDelayMs();
            playable.addAll(state.getBuffer().pollDue(now, delayMs, globalBudget));
        }
        if (maxCount > 0 && playable.size() > maxCount) {
            return new ArrayList<>(playable.subList(0, (int) maxCount));
        }
        return playable;
    }

    public byte[] buildRtcpReport() {
        List<ReportBlock> blocks = new ArrayList<>();
        long nowNtp = NtpTime.fromMillis(clock.currentTimeMillis());
        for (SsrcState state : ssrcStates.values()) {
            state.getStats().recordSentReceiverReport(nowNtp);
            blocks.add(state.getStats().buildReportBlock(nowNtp));
        }
        ReceiverReport report = new ReceiverReport(localSsrc, blocks);
        return rtcpCodec.encodeCompound(List.of(report));
    }

    public void close() {
        closed = true;
        if (rtcpScheduler != null) {
            rtcpScheduler.stop();
            rtcpScheduler = null;
        }
        for (SsrcState state : ssrcStates.values()) {
            state.getBuffer().clear(globalBudget);
        }
        ssrcStates.clear();
    }

    public boolean isClosed() {
        return closed;
    }

    public SessionCounters getCounters() {
        return counters;
    }

    public Collection<ReceptionStats> getReceptionStats() {
        return ssrcStates.values().stream().map(SsrcState::getStats).toList();
    }

    public synchronized List<SsrcCollisionEvent> getCollisionEvents() {
        return List.copyOf(collisionEvents);
    }

    public int bufferedPackets() {
        return ssrcStates.values().stream().mapToInt(state -> state.getBuffer().size()).sum();
    }

    /**
     * Returns the current adaptive playout delay in milliseconds for a given
     * SSRC, or -1 if the source is unknown.
     */
    public long currentDelayMs(long ssrc) {
        SsrcState state = ssrcStates.get(ssrc);
        return state == null ? -1 : state.getDelayController().currentDelayMs();
    }

    RtpParser getParser() {
        return parser;
    }

    RtcpCodec getRtcpCodec() {
        return rtcpCodec;
    }
}
