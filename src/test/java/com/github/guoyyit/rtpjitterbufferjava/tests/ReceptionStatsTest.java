package com.github.guoyyit.rtpjitterbufferjava.tests;

import com.github.guoyyit.rtpjitterbufferjava.clock.TestClock;
import com.github.guoyyit.rtpjitterbufferjava.rtcp.ReportBlock;
import com.github.guoyyit.rtpjitterbufferjava.stats.ReceptionStats;
import com.github.guoyyit.rtpjitterbufferjava.stats.SeqStatus;
import com.github.guoyyit.rtpjitterbufferjava.stats.SeqUpdate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceptionStatsTest {

    private final TestClock clock = new TestClock();

    @Test
    void tracksInOrderSequenceNumbers() {
        ReceptionStats stats = new ReceptionStats(42, 8000, clock);

        for (int i = 100; i < 110; i++) {
            SeqUpdate update = stats.updateSequence(i);
            assertEquals(SeqStatus.ACCEPTED, update.status());
            assertEquals(i, update.extendedSequence());
        }

        assertEquals(10, stats.getReceivedPackets());
        assertEquals(0, stats.getCumulativeLost());
        assertEquals(109, stats.getExtendedHighestSequence());
    }

    @Test
    void detectsDuplicatesAndReordering() {
        ReceptionStats stats = new ReceptionStats(1, 8000, clock);

        assertEquals(SeqStatus.ACCEPTED, stats.updateSequence(10).status());
        assertEquals(SeqStatus.ACCEPTED, stats.updateSequence(11).status());
        assertEquals(SeqStatus.ACCEPTED, stats.updateSequence(12).status());
        assertEquals(SeqStatus.DUPLICATE, stats.updateSequence(11).status());
        assertEquals(SeqStatus.REORDERED, stats.updateSequence(9).status());

        assertEquals(1, stats.getDuplicatePackets());
        assertEquals(1, stats.getReorderedPackets());
        assertEquals(0, stats.getCumulativeLost());
    }

    @Test
    void countsLossAcrossGaps() {
        ReceptionStats stats = new ReceptionStats(1, 8000, clock);

        for (int seq : new int[] {0, 1, 2, 5, 6}) {
            stats.updateSequence(seq);
        }

        assertEquals(5, stats.getReceivedPackets());
        assertEquals(2, stats.getCumulativeLost());
    }

    @Test
    void wrapsSequenceNumbersAt16BitBoundary() {
        ReceptionStats stats = new ReceptionStats(1, 8000, clock);

        for (int seq = 0xFFF8; seq <= 0xFFFF; seq++) {
            stats.updateSequence(seq);
        }
        for (int seq = 0; seq < 8; seq++) {
            SeqUpdate update = stats.updateSequence(seq);
            assertEquals(SeqStatus.ACCEPTED, update.status());
        }

        assertEquals(16, stats.getReceivedPackets());
        assertEquals(0, stats.getCumulativeLost());
        assertEquals(0x10007L, stats.getExtendedHighestSequence());
    }

    @Test
    void reordersAcrossWrapBoundary() {
        ReceptionStats stats = new ReceptionStats(1, 8000, clock);

        stats.updateSequence(0xFFFF);
        stats.updateSequence(0);
        stats.updateSequence(1);
        SeqUpdate lateZero = stats.updateSequence(0xFFFE);

        assertEquals(SeqStatus.REORDERED, lateZero.status());
        assertEquals(0xFFFEL, lateZero.extendedSequence());
        assertEquals(0, stats.getCumulativeLost());
    }

    @Test
    void estimatesJitterFromTransitVariation() {
        ReceptionStats stats = new ReceptionStats(1, 8000, clock);

        clock.setTime(0);
        stats.updateSequence(0);
        stats.updateJitter(0, 0);

        clock.setTime(20);
        stats.updateSequence(1);
        stats.updateJitter(160, 20);

        clock.setTime(40);
        stats.updateSequence(2);
        stats.updateJitter(320, 40);

        clock.setTime(80);
        stats.updateSequence(3);
        stats.updateJitter(480, 80);

        assertTrue(stats.getJitter() > 0, "jitter should reflect the 20ms transit spike");
    }

    @Test
    void buildsReportBlockWithFractionAndCumulativeLoss() {
        ReceptionStats stats = new ReceptionStats(1, 8000, clock);

        stats.updateSequence(0);
        stats.updateSequence(3);

        ReportBlock block = stats.buildReportBlock(0);

        assertEquals(1, block.ssrc());
        assertEquals(2, block.cumulativeLost());
        assertTrue(block.fractionLost() > 0);
    }
}
