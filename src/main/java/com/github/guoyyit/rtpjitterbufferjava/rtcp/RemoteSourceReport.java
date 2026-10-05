package com.github.guoyyit.rtpjitterbufferjava.rtcp;

/**
 * RTCP reception state for one remote SSRC: remembers the most recent sender
 * report so the next receiver report can fill LSR/DLSR (RFC 3550 6.4.1).
 *
 * <p>LSR is the middle 32 bits of the peer's SR NTP timestamp. DLSR is the
 * local wall-clock time between receiving that SR and sending the RR, measured
 * in the same NTP domain the sender uses to compute {@code A - LSR - DLSR}.
 */
public final class RemoteSourceReport {

    private final long remoteSsrc;

    private long lastSrMiddle32;
    private long lastSrArrivalNtp;

    public RemoteSourceReport(long remoteSsrc) {
        this.remoteSsrc = remoteSsrc & 0xFFFFFFFFL;
    }

    public long remoteSsrc() {
        return remoteSsrc;
    }

    /** Records an incoming SR and the local wall-clock NTP time it arrived. */
    public void onSenderReport(long srNtpTimestamp, long arrivalNtp) {
        this.lastSrMiddle32 = NtpTime.middle32(srNtpTimestamp);
        this.lastSrArrivalNtp = arrivalNtp;
    }

    /** Middle 32 bits of the last SR NTP timestamp; 0 until an SR is seen. */
    public long lastSr() {
        return lastSrMiddle32;
    }

    /** DLSR in 1/65536 s between SR arrival and the given local NTP time. */
    public long delaySinceLastSrUnitsByNtp(long nowNtp) {
        if (lastSrArrivalNtp == 0L) {
            return 0L;
        }
        long nanos = NtpTime.diffNanos(nowNtp, lastSrArrivalNtp);
        if (nanos <= 0L) {
            return 0L;
        }
        return nanos * 65536L / 1_000_000_000L;
    }
}
