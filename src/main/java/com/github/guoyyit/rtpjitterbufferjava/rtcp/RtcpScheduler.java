package com.github.guoyyit.rtpjitterbufferjava.rtcp;

import com.github.guoyyit.rtpjitterbufferjava.transport.PacketTransport;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends RTCP reports on a fixed cadence using a daemon thread. Report
 * construction and network I/O happen off the media receive path, and send
 * failures are swallowed so they can never block or stop media processing.
 */
public final class RtcpScheduler {

    private final String name;
    private final long intervalMillis;
    private final ReportSupplier reportSupplier;

    private PacketTransport transport;
    private SocketAddress target;
    private Thread thread;
    private final AtomicBoolean running = new AtomicBoolean();

    @FunctionalInterface
    public interface ReportSupplier {
        byte[] buildReport();
    }

    public RtcpScheduler(String name, long intervalMillis, ReportSupplier reportSupplier) {
        this.name = name;
        this.intervalMillis = intervalMillis;
        this.reportSupplier = reportSupplier;
    }

    public void start(PacketTransport transport, SocketAddress target) {
        this.transport = transport;
        this.target = target;
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::runLoop, "rtcp-sender-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private void runLoop() {
        while (running.get()) {
            try {
                Thread.sleep(intervalMillis);
                byte[] report = reportSupplier.buildReport();
                if (report.length > 0 && transport != null && transport.isOpen()) {
                    transport.sendRtcp(report, target);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException | RuntimeException e) {
                // Sending RTCP must never disturb the media path.
            }
        }
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
    }
}
