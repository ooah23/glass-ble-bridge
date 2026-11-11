package net.n.glassbridge.core;

/** Constants shared with the iPhone app. Keep in sync with docs/PROTOCOL.md and ios/GlassBridge/Protocol.swift. */
public final class Protocol {
    private Protocol() {}
    public static final String SERVICE = "8a6f0000-5b1e-4c3a-9d2f-7e1b0c4a6d10";
    /** Glass writes here (glass to phone). */
    public static final String RX_CHAR = "8a6f0001-5b1e-4c3a-9d2f-7e1b0c4a6d10";
    /** Phone notifies here (phone to glass). */
    public static final String TX_CHAR = "8a6f0002-5b1e-4c3a-9d2f-7e1b0c4a6d10";
    public static final String CCCD = "00002902-0000-1000-8000-00805f9b34fb";
    public static final String LOCAL_NAME = "GlassBridge";
    public static final int FRAME_SIZE = 20;
    public static final int MAX_MESSAGE = 4096;
}
