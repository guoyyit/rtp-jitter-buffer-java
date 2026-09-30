package com.github.guoyyit.rtpjitterbufferjava.session;

public record SsrcCollisionEvent(long ssrc, String reason, long timestampMillis) {
}
