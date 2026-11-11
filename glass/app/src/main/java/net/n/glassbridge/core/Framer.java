package net.n.glassbridge.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Frame format: 1 header byte + up to 19 payload bytes.
 * Header: bit7 START, bit6 END, bits0-5 sequence (mod 64, 0 at START).
 * Message bytes = u16 big-endian length + UTF-8 JSON, split across frames.
 */
public final class Framer {
    private Framer() {}
    public static final int MAX_PAYLOAD = Protocol.FRAME_SIZE - 1;
    public static final int START = 0x80, END = 0x40, SEQ_MASK = 0x3f;

    public static List<byte[]> chunk(byte[] json) {
        if (json.length > Protocol.MAX_MESSAGE) throw new IllegalArgumentException("message too large");
        byte[] buf = new byte[json.length + 2];
        buf[0] = (byte) (json.length >> 8);
        buf[1] = (byte) json.length;
        System.arraycopy(json, 0, buf, 2, json.length);
        List<byte[]> out = new ArrayList<byte[]>();
        int off = 0, seq = 0;
        while (off < buf.length) {
            int n = Math.min(MAX_PAYLOAD, buf.length - off);
            int h = seq & SEQ_MASK;
            if (off == 0) h |= START;
            if (off + n == buf.length) h |= END;
            byte[] f = new byte[n + 1];
            f[0] = (byte) h;
            System.arraycopy(buf, off, f, 1, n);
            out.add(f);
            off += n;
            seq++;
        }
        return out;
    }

    /** Stateful reassembler. feed() returns a complete JSON byte[] or null. Bad frames bump errors and reset. */
    public static final class Reassembler {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private boolean active;
        private int nextSeq;
        public int errors;

        public byte[] feed(byte[] frame) {
            if (frame == null || frame.length < 2 || frame.length > Protocol.FRAME_SIZE) { fail(); return null; }
            int h = frame[0] & 0xff;
            int seq = h & SEQ_MASK;
            if ((h & START) != 0) {
                buf.reset();
                active = true;
                nextSeq = 0;
            } else if (!active) {
                errors++;
                return null;
            }
            if (seq != nextSeq) { fail(); return null; }
            nextSeq = (nextSeq + 1) & SEQ_MASK;
            buf.write(frame, 1, frame.length - 1);
            if (buf.size() > Protocol.MAX_MESSAGE + 2) { fail(); return null; }
            if ((h & END) == 0) return null;
            byte[] all = buf.toByteArray();
            active = false;
            buf.reset();
            if (all.length < 2) { errors++; return null; }
            int len = ((all[0] & 0xff) << 8) | (all[1] & 0xff);
            if (len != all.length - 2) { errors++; return null; }
            byte[] json = new byte[len];
            System.arraycopy(all, 2, json, 0, len);
            return json;
        }

        private void fail() { errors++; active = false; buf.reset(); }
    }
}
