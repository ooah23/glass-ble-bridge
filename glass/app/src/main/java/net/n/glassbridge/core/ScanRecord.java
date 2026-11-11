package net.n.glassbridge.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Minimal advertisement parser (API 19 has no ScanRecord class). */
public final class ScanRecord {
    public String localName;
    public final List<String> serviceUuids = new ArrayList<String>();

    public static ScanRecord parse(byte[] r) {
        ScanRecord s = new ScanRecord();
        if (r == null) return s;
        int i = 0;
        while (i < r.length) {
            int len = r[i] & 0xff;
            if (len == 0 || i + 1 + len > r.length) break;
            int type = r[i + 1] & 0xff;
            int dataStart = i + 2, dataLen = len - 1;
            if (type == 0x08 || type == 0x09) {
                s.localName = new String(r, dataStart, dataLen);
            } else if (type == 0x06 || type == 0x07) {
                for (int o = 0; o + 16 <= dataLen; o += 16) s.serviceUuids.add(uuid128(r, dataStart + o));
            }
            i += len + 1;
        }
        return s;
    }

    private static String uuid128(byte[] r, int o) {
        StringBuilder sb = new StringBuilder();
        for (int k = 15; k >= 0; k--) {
            sb.append(String.format("%02x", r[o + k] & 0xff));
            if (k == 12 || k == 10 || k == 8 || k == 6) sb.append('-');
        }
        return sb.toString();
    }

    /** True for our peripheral: advertises our service UUID, or has the fixed local name. */
    public boolean matches() {
        if (Protocol.LOCAL_NAME.equals(localName)) return true;
        for (String u : serviceUuids) if (u.toLowerCase(Locale.US).equals(Protocol.SERVICE)) return true;
        return false;
    }
}
