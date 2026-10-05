package com.github.guoyyit.rtpjitterbufferjava.rtcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Periodic, non-blocking RTCP scheduler for one session. Reporting runs on a
 * dedicated daemon single-thread executor so it never blocks media handling.
 */
public class RtcpReporter {

    /** Snapshot of everything needed to emit one compound report. */
    public interface ReportSource {
        long localSsrc();

        String cname();

        List<ReportBlock> reportBlocks();

        SenderInfo senderInfo();

        /** Invoked after a compound report was handed to the sink successfully. */
        default void onReportSent() {
        }
    }

    /** Sink for encoded RTCP datagrams (typically a PacketTransport). */
    public interface RtcpSink {
        void send(byte[] data) throws IOException;
    }

    private final ReportSource reportSource;
    private final RtcpSink sink;
    private final Duration interval;
    private final RtcpPacketBuilder builder = new RtcpPacketBuilder();
    private final LongSupplier clockMillis;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> task;

    public RtcpReporter(ReportSource reportSource, RtcpSink sink, Duration interval) {
        this(reportSource, sink, interval, System::currentTimeMillis);
    }

    public RtcpReporter(ReportSource reportSource, RtcpSink sink, Duration interval,
                        LongSupplier clockMillis) {
        this.reportSource = reportSource;
        this.sink = sink;
        this.interval = interval;
        this.clockMillis = clockMillis;
    }

    public void start() {
        if (executor != null) {
            return;
        }
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "rtcp-reporter-" + reportSource.localSsrc());
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newSingleThreadScheduledExecutor(factory);
        long periodMillis = Math.max(1, interval.toMillis());
        task = executor.scheduleAtFixedRate(this::emitSafely, periodMillis,
                periodMillis, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** Builds and sends one report immediately; visible for testing. */
    public void emitOnce() {
        SenderInfo senderInfo = reportSource.senderInfo();
        byte[] compound = senderInfo == null
                ? builder.buildReceiverReport(reportSource.localSsrc(),
                        reportSource.cname(), reportSource.reportBlocks())
                : builder.buildSenderReport(reportSource.localSsrc(),
                        reportSource.cname(), senderInfo, reportSource.reportBlocks());
        try {
            sink.send(compound);
            reportSource.onReportSent();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Builds a report without a scheduler; useful for deterministic tests. */
    public byte[] buildCurrentReport() {
        SenderInfo senderInfo = reportSource.senderInfo();
        return senderInfo == null
                ? builder.buildReceiverReport(reportSource.localSsrc(),
                        reportSource.cname(), reportSource.reportBlocks())
                : builder.buildSenderReport(reportSource.localSsrc(),
                        reportSource.cname(), senderInfo, reportSource.reportBlocks());
    }

    /** Encodes a receiver report from externally assembled blocks. */
    public byte[] buildReceiverReport(long localSsrc, String cname, List<ReportBlock> blocks) {
        return builder.buildReceiverReport(localSsrc, cname, blocks);
    }

    private void emitSafely() {
        try {
            emitOnce();
        } catch (RuntimeException ignored) {
            // A transport hiccup must never kill the periodic task.
        }
    }

    public boolean running() {
        return executor != null;
    }

    /** Delay since last SR in 1/65536-second units (RFC 3550 6.4.1 DLSR). */
    public static long dlsrUnits(long lastSrNtp, long nowNtp) {
        if (lastSrNtp == 0) {
            return 0;
        }
        long nanos = NtpTime.diffNanos(nowNtp, lastSrNtp);
        double seconds = nanos / 1_000_000_000.0;
        return Math.max(0, Math.round(seconds * 65536.0));
    }
}
