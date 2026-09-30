package com.github.guoyyit.rtpjitterbufferjava.rtp;

import java.util.Set;

/**
 * Registry of payload types accepted by a session.
 */
public class RtpPayloadTypes {

    private static final Set<Integer> STATIC_PAYLOAD_TYPES = Set.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
            25, 26, 28, 31, 32, 33, 34
    );

    private final Set<Integer> allowed;

    private RtpPayloadTypes(Set<Integer> allowed) {
        this.allowed = Set.copyOf(allowed);
    }

    /**
     * Accepts every well-formed payload type 0..127, including dynamic types.
     */
    public static RtpPayloadTypes all() {
        java.util.HashSet<Integer> all = new java.util.HashSet<>();
        for (int pt = 0; pt < 128; pt++) {
            all.add(pt);
        }
        return new RtpPayloadTypes(all);
    }

    /**
     * Accepts the given payload types; any statically assigned type in the
     * argument is accepted even without explicit registration.
     */
    public static RtpPayloadTypes of(int... payloadTypes) {
        java.util.HashSet<Integer> set = new java.util.HashSet<>(STATIC_PAYLOAD_TYPES);
        for (int pt : payloadTypes) {
            if (pt < 0 || pt > 127) {
                throw new IllegalArgumentException("payload type out of range: " + pt);
            }
            set.add(pt);
        }
        return new RtpPayloadTypes(set);
    }

    public Set<Integer> allowed() {
        return allowed;
    }

    public boolean isKnown(int payloadType) {
        return payloadType >= 0 && payloadType <= 127 && allowed.contains(payloadType);
    }
}
