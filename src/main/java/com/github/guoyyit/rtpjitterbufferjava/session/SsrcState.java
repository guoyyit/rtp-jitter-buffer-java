package com.github.guoyyit.rtpjitterbufferjava.session;

import com.github.guoyyit.rtpjitterbufferjava.buffer.AdaptiveDelayController;
import com.github.guoyyit.rtpjitterbufferjava.buffer.JitterBuffer;
import com.github.guoyyit.rtpjitterbufferjava.stats.ReceptionStats;

import java.net.SocketAddress;

public class SsrcState {

    private final long ssrc;
    private final ReceptionStats stats;
    private final JitterBuffer buffer;
    private final AdaptiveDelayController delayController;

    private volatile SocketAddress source;
    private volatile int collisionCount;
    private volatile boolean terminated;

    public SsrcState(long ssrc, ReceptionStats stats, JitterBuffer buffer,
                     AdaptiveDelayController delayController, SocketAddress source) {
        this.ssrc = ssrc;
        this.stats = stats;
        this.buffer = buffer;
        this.delayController = delayController;
        this.source = source;
    }

    public long getSsrc() {
        return ssrc;
    }

    public ReceptionStats getStats() {
        return stats;
    }

    public JitterBuffer getBuffer() {
        return buffer;
    }

    public AdaptiveDelayController getDelayController() {
        return delayController;
    }

    public SocketAddress getSource() {
        return source;
    }

    public void setSource(SocketAddress source) {
        this.source = source;
    }

    public int recordCollision() {
        return ++collisionCount;
    }

    public int getCollisionCount() {
        return collisionCount;
    }

    public void terminate() {
        this.terminated = true;
    }

    public boolean isTerminated() {
        return terminated;
    }
}
