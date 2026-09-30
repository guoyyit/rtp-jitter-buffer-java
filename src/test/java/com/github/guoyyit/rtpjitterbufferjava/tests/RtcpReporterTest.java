package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.rtcp.NtpTime;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.ReportBlock;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.RtcpReporter;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.SenderInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtcpReporterTest {

    private static RtcpReporter.ReportSource source() {
        return new RtcpReporter.ReportSource() {
            @Override
            public long localSsrc() {
                return 99;
            }

            @Override
            public String cname() {
                return "reporter@test";
            }

            @Override
            public List<ReportBlock> reportBlocks() {
                return List.of(new ReportBlock(7, 0, 0, 0, 0, 0, 0));
            }

            @Override
            public SenderInfo senderInfo() {
                return null;
            }
        };
    }

    @Test
    void emitsReportsPeriodicallyWithoutBlockingCaller() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        RtcpReporter reporter = new RtcpReporter(source(), data -> sends.incrementAndGet(),
                Duration.ofMillis(20));
        reporter.start();
        Thread.sleep(150);
        reporter.stop();
        assertTrue(sends.get() >= 3, "expected periodic reports, got " + sends.get());
    }

    @Test
    void keepsReportingWhenSinkThrows() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        RtcpReporter reporter = new RtcpReporter(source(), data -> {
            attempts.incrementAndGet();
            throw new IOException("simulated transport failure");
        }, Duration.ofMillis(15));
        reporter.start();
        Thread.sleep(120);
        reporter.stop();
        assertTrue(attempts.get() >= 3, "reporter must survive sink failures: " + attempts.get());
    }

    @Test
    void dlsrReflectsElapsedTimeSinceLastSenderReport() {
        long lsr = NtpTime.fromJavaMillis(1_000_000);
        long now = NtpTime.fromJavaMillis(1_000_000 + 1000);
        long dlsr = RtcpReporter.dlsrUnits(lsr, now);
        // 1 second expressed in 1/65536 seconds
        assertEquals(65536L, dlsr, 64);
        assertEquals(0L, RtcpReporter.dlsrUnits(0, now));
    }
}
