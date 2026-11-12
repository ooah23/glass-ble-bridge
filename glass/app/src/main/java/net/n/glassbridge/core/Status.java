package net.n.glassbridge.core;

/** Human-readable link status and GATT status names. Same wording as ios/GlassBridge/LinkState.swift. */
public final class Status {
    private Status() {}

    public static String gattName(int status) {
        switch (status) {
            case 0: return "success";
            case 8: return "supervision timeout";
            case 19: return "remote terminated";
            case 22: return "local terminated";
            case 133: return "GATT_ERROR";
            default: return "unknown";
        }
    }

    public static String reason(int status) { return "gatt " + status + " " + gattName(status); }

    /** phase: scanning, connecting, authenticating, paired, lost. reason only for lost. ageSec -1 = never seen. */
    public static String text(String phase, String reason, long ageSec) {
        String s;
        if ("lost".equals(phase)) s = "Link lost (" + reason + ")";
        else if ("scanning".equals(phase)) s = "Scanning";
        else if ("connecting".equals(phase)) s = "Connecting";
        else if ("authenticating".equals(phase)) s = "Authenticating";
        else if ("paired".equals(phase)) s = "Paired";
        else s = phase;
        if (ageSec >= 0 && ("paired".equals(phase) || "lost".equals(phase) || "scanning".equals(phase))) s += " - last seen " + ageSec + "s ago";
        return s;
    }
}
