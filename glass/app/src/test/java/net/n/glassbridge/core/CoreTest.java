package net.n.glassbridge.core;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Random;

/** Plain-Java tests (no JUnit, no Android): run with scripts/test-glass.sh. */
public class CoreTest {
    static final Charset UTF8 = Charset.forName("UTF-8");
    static int checks;

    static void eq(Object a, Object b, String what) {
        checks++;
        if (a == null ? b != null : !a.equals(b)) throw new AssertionError(what + ": " + a + " != " + b);
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    public static void main(String[] a) {
        Random rnd = new Random(7);
        // round trip across sizes, including frame-boundary sizes
        int[] sizes = {0, 1, 16, 17, 18, 19, 20, 36, 37, 38, 100, 1000, 4096};
        for (int n : sizes) {
            byte[] msg = new byte[n];
            rnd.nextBytes(msg);
            List<byte[]> frames = Framer.chunk(msg);
            Framer.Reassembler r = new Framer.Reassembler();
            byte[] got = null;
            for (int i = 0; i < frames.size(); i++) {
                eq(frames.get(i).length <= 20, true, "frame<=20");
                byte[] o = r.feed(frames.get(i));
                if (i < frames.size() - 1) eq(o, null, "partial null");
                else got = o;
            }
            eq(hex(got), hex(msg), "roundtrip " + n);
            eq(r.errors, 0, "no errors");
        }
        // back-to-back messages on one reassembler (seq wraps past 64 frames)
        Framer.Reassembler r = new Framer.Reassembler();
        byte[] big = new byte[2000];
        rnd.nextBytes(big);
        for (int k = 0; k < 3; k++) {
            byte[] o = null;
            for (byte[] f : Framer.chunk(big)) o = r.feed(f);
            eq(hex(o), hex(big), "wrap " + k);
        }
        // errors: dropped middle frame, orphan continuation, truncated, bad length
        r = new Framer.Reassembler();
        List<byte[]> fr = Framer.chunk(new byte[100]);
        r.feed(fr.get(0)); r.feed(fr.get(2));
        eq(r.errors, 1, "gap detected");
        eq(r.feed(fr.get(3)), null, "orphan dropped");
        byte[] good = null;
        for (byte[] f : Framer.chunk("{\"t\":\"x\"}".getBytes(UTF8))) good = r.feed(f);
        eq(new String(good, UTF8), "{\"t\":\"x\"}", "recovers after error");
        r = new Framer.Reassembler();
        eq(r.feed(unhex("c0000563")), null, "length mismatch rejected");
        eq(r.feed(new byte[]{1}), null, "short frame rejected");
        try { Framer.chunk(new byte[Protocol.MAX_MESSAGE + 1]); throw new AssertionError("no throw"); }
        catch (IllegalArgumentException expected) { checks++; }

        // shared vector (also asserted in ios/Tests/main.swift)
        String vec = "{\"t\":\"card\",\"text\":\"Hello Glass\"}";
        List<byte[]> vf = Framer.chunk(vec.getBytes(UTF8));
        StringBuilder sb = new StringBuilder();
        for (byte[] f : vf) sb.append(hex(f)).append(' ');
        eq(sb.toString().trim(), VECTOR_FRAMES, "framing vector");

        eq(Auth.response("test-secret", "00112233445566778899aabbccddeeff"),
           "84b7660ccd62a3d5848a05112cd1cff4e753779cb4464c156e0e57d7c9b6cef3", "hmac vector");

        // scan record: flags, 128-bit uuid (little endian), complete name
        StringBuilder le = new StringBuilder();
        String flat = Protocol.SERVICE.replace("-", "");
        for (int i = 30; i >= 0; i -= 2) le.append(flat, i, i + 2);
        byte[] rec = unhex("020106" + "1107" + le + "0c09" + hex("GlassBridge".getBytes(UTF8)));
        ScanRecord s = ScanRecord.parse(rec);
        eq(s.localName, "GlassBridge", "name");
        eq(s.serviceUuids.get(0), Protocol.SERVICE, "uuid");
        eq(s.matches(), true, "match");
        eq(ScanRecord.parse(unhex("020106")).matches(), false, "no match");
        eq(ScanRecord.parse(unhex("0209")).matches(), false, "truncated safe");

        Backoff b = new Backoff();
        eq(b.next(), 2000L, "b1"); eq(b.next(), 4000L, "b2");
        for (int i = 0; i < 10; i++) b.next();
        eq(b.next(), 60000L, "cap");
        b.reset();
        eq(b.next(), 2000L, "reset");
        System.out.println("OK " + checks + " checks");
    }

    static final String VECTOR_FRAMES = "8000217b2274223a2263617264222c2274657874 41223a2248656c6c6f20476c617373227d";
}
