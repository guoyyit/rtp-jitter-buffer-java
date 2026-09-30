package com.github.guoyyit.rtpjitterbufferjava.rtcp;

import java.util.Map;

/**
 * Parsed RTCP source description items (CNAME etc.).
 */
public class RtcpSdes extends RtcpMessage {

    private final Map<Long, String> cnames;

    public RtcpSdes(Map<Long, String> cnames) {
        this.cnames = Map.copyOf(cnames);
    }

    public Map<Long, String> cnames() {
        return cnames;
    }
}
