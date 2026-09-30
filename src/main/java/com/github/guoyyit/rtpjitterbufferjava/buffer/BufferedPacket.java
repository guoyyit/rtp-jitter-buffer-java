package com.github.guoyyit.rtpjitterbufferjava.buffer;

import com.github.guoyyit.rtpjitterbufferjava.model.RtpPacket;

public record BufferedPacket(RtpPacket packet, long arrivalMillis, long extendedSequence) {
}
