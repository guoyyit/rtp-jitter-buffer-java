package com.github.guoyyit.rtpjitterbufferjava.buffer;

import com.github.guoyyit.rtpjitterbufferjava.rtp.RtpPacket;
import com.github.guoyyit.rtpjitterbufferjava.rtp.SeqNum;
import com.github.guoyyit.rtpjitterbufferjava.rtp.TimeStamp32;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Per-SSRC bounded reordering and playout buffer.
 */
public class JitterBuffer {

    private final long ssrc;
    private final JitterBufferConfig config;
    private final long clockRate;
    private final NavigableMap<Long, RtpPacket> packets = new TreeMap<>();
    private final JitterEstimator jitterEstimator = new JitterEstimator();
    private final AdaptivePlayoutController playout;

    private boolean initialized;
    private long highestExtended;
    private long lastArrivalNanos;
    private long lastRtpTimestamp;
    private long nextPlaySequence;
    private long playOriginArrivalNanos;
    private long playOriginRtpTimestamp;

    public JitterBuffer(long ssrc, JitterBufferConfig config) {
        this(ssrc, config, 1000L);
    }

    public JitterBuffer(long ssrc, JitterBufferConfig config, long clockRate) {
        this.ssrc = ssrc;
        this.config = config;
        this.clockRate = clockRate;
        this.playout = new AdaptivePlayoutController(config);
    }

    public long ssrc() {
        return ssrc;
    }

    /**
     * Classifies an inbound packet and stores it when it is still playable.
     */
    public AdmissionOutcome admit(RtpPacket packet) {
        int seq = packet.header().sequenceNumber();
        long arrivalNanos = packet.arrivalNanos();

        if (!initialized) {
            initialize(packet);
            return insert(packet, highestExtended);
        }

        long extended = SeqNum.unwrap(highestExtended, seq);
        if (extended < nextPlaySequence) {
            return AdmissionOutcome.LATE;
        }
        if (packets.containsKey(extended)) {
            return AdmissionOutcome.DUPLICATE;
        }
        if (extended - highestExtended > config.reorderWindowPackets()) {
            return AdmissionOutcome.REORDER_WINDOW_EXCEEDED;
        }
        if (extended > highestExtended) {
            highestExtended = extended;
        }

        long arrivalDelta = arrivalNanos - lastArrivalNanos;
        long rtpDeltaNanos = rtpToNanos(
                TimeStamp32.diff(packet.header().timestamp(), lastRtpTimestamp));
        if (arrivalDelta > 0) {
            jitterEstimator.update(arrivalDelta, rtpDeltaNanos);
            playout.onJitterUpdated(jitterEstimator.jitterNanos());
        }
        lastArrivalNanos = arrivalNanos;
        lastRtpTimestamp = packet.header().timestamp();

        return insert(packet, extended);
    }

    /**
     * Removes and returns every packet whose scheduled playout time is due.
     */
    public List<RtpPacket> due(long nowNanos) {
        List<RtpPacket> ready = new ArrayList<>();
        if (!initialized) {
            return ready;
        }
        for (Map.Entry<Long, RtpPacket> entry : packets.entrySet()) {
            if (playoutTimeNanos(entry.getValue()) > nowNanos) {
                break;
            }
            ready.add(entry.getValue());
        }
        for (RtpPacket packet : ready) {
            packets.remove(SeqNum.unwrap(highestExtended, packet.header().sequenceNumber()));
        }
        if (!ready.isEmpty()) {
            long lastSeq = SeqNum.unwrap(highestExtended,
                    ready.get(ready.size() - 1).header().sequenceNumber());
            nextPlaySequence = lastSeq + 1;
        }
        return ready;
    }

    public int size() {
        return packets.size();
    }

    /** Lowest extended sequence that is still playable (anything below is late). */
    public long nextPlaySequence() {
        return nextPlaySequence;
    }

    public void flush() {
        packets.clear();
        if (initialized) {
            nextPlaySequence = highestExtended + 1;
        }
    }

    public long currentDelayNanos() {
        return playout.currentDelayNanos();
    }

    public long jitterNanos() {
        return jitterEstimator.jitterNanos();
    }

    private void initialize(RtpPacket packet) {
        initialized = true;
        highestExtended = packet.header().sequenceNumber() & 0xFFFFL;
        nextPlaySequence = highestExtended;
        lastArrivalNanos = packet.arrivalNanos();
        lastRtpTimestamp = packet.header().timestamp();
        playOriginArrivalNanos = packet.arrivalNanos();
        playOriginRtpTimestamp = packet.header().timestamp();
    }

    private AdmissionOutcome insert(RtpPacket packet, long extended) {
        if (packets.size() >= config.maxPacketsPerSsrc()) {
            return AdmissionOutcome.BUFFER_FULL;
        }
        packets.put(extended, packet);
        return AdmissionOutcome.ACCEPTED;
    }

    private long playoutTimeNanos(RtpPacket packet) {
        long rtpDelta = TimeStamp32.diff(packet.header().timestamp(), playOriginRtpTimestamp);
        return playOriginArrivalNanos + rtpToNanos(rtpDelta) + playout.currentDelayNanos();
    }

    private long rtpToNanos(long rtpDelta) {
        return rtpDelta * 1_000_000_000L / clockRate;
    }
}
