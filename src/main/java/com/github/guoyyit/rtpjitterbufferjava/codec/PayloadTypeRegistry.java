package com.github.guoyyit.rtpjitterbufferjava.codec;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class PayloadTypeRegistry {

    private static final Map<Integer, Integer> RFC3551_CLOCK_RATES = Map.ofEntries(
            Map.entry(0, 8000),
            Map.entry(1, 8000),
            Map.entry(3, 8000),
            Map.entry(4, 8000),
            Map.entry(5, 8000),
            Map.entry(6, 8000),
            Map.entry(7, 8000),
            Map.entry(8, 8000),
            Map.entry(9, 8000),
            Map.entry(10, 44100),
            Map.entry(11, 44100),
            Map.entry(12, 8000),
            Map.entry(13, 8000),
            Map.entry(14, 90000),
            Map.entry(15, 8000),
            Map.entry(16, 11025),
            Map.entry(17, 11025),
            Map.entry(18, 8000),
            Map.entry(25, 90000),
            Map.entry(26, 90000),
            Map.entry(28, 90000),
            Map.entry(31, 90000),
            Map.entry(32, 90000),
            Map.entry(33, 90000),
            Map.entry(34, 90000)
    );

    private final Set<Integer> whitelist;
    private final int dynamicClockRate;
    private final Map<Integer, Integer> dynamicRates;

    /**
     * @param allowedPayloadTypes when non-empty, only payload types in this set are accepted;
     *                            when empty the RFC 3551 static types plus dynamic 96-127 are used
     * @param dynamicClockRate clock rate used for non-static (dynamic) payload types
     */
    public PayloadTypeRegistry(Set<Integer> allowedPayloadTypes, int dynamicClockRate) {
        this(allowedPayloadTypes, dynamicClockRate, Map.of());
    }

    public PayloadTypeRegistry(Set<Integer> allowedPayloadTypes, int defaultDynamicClockRate,
                               Map<Integer, Integer> dynamicClockRates) {
        if (defaultDynamicClockRate <= 0) {
            throw new IllegalArgumentException("dynamicClockRate must be positive");
        }
        this.whitelist = Set.copyOf(allowedPayloadTypes);
        this.dynamicClockRate = defaultDynamicClockRate;
        this.dynamicRates = new HashMap<>(dynamicClockRates);
    }

    public static PayloadTypeRegistry rfc3551() {
        return new PayloadTypeRegistry(Set.of(), 90000);
    }

    public boolean isKnown(int payloadType) {
        if (payloadType < 0 || payloadType > 127) {
            return false;
        }
        if (!whitelist.isEmpty()) {
            return whitelist.contains(payloadType);
        }
        return RFC3551_CLOCK_RATES.containsKey(payloadType) || isDynamic(payloadType);
    }

    public int clockRate(int payloadType) {
        Integer explicit = dynamicRates.get(payloadType);
        if (explicit != null) {
            return explicit;
        }
        Integer rate = RFC3551_CLOCK_RATES.get(payloadType);
        if (rate != null) {
            return rate;
        }
        return dynamicClockRate;
    }

    public void registerDynamicPayloadType(int payloadType, int clockRate) {
        if (!isDynamic(payloadType)) {
            throw new IllegalArgumentException("only dynamic payload types 96-127 can be registered");
        }
        if (clockRate <= 0) {
            throw new IllegalArgumentException("clockRate must be positive");
        }
        dynamicRates.put(payloadType, clockRate);
    }

    private static boolean isDynamic(int payloadType) {
        return payloadType >= 96 && payloadType <= 127;
    }
}
