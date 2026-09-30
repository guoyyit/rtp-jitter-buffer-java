package com.github.guoyyit.rtpjitterbufferjava.rtcp;

/**
 * Common base of parsed RTCP packets (RFC 3550 section 6).
 */
public abstract class RtcpMessage {

    public static final int PT_SR = 200;
    public static final int PT_RR = 201;
    public static final int PT_SDES = 202;
    public static final int PT_BYE = 203;
    public static final int PT_APP = 204;
}
