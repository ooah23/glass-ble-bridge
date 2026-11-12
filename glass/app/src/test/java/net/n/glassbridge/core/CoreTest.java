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
        eq(b.next(), 30000L, "cap");
        b.reset();
        eq(b.next(), 2000L, "reset");
        LinkMonitor m = new LinkMonitor();
        m.start(1000);
        eq(m.tick(1000), LinkMonitor.SEND, "hb first send");
        eq(m.tick(3000), LinkMonitor.NONE, "hb quiet");
        eq(m.tick(6000), LinkMonitor.SEND, "hb 5s send");
        eq(m.counter(), 2, "hb counter");
        eq(m.ageSeconds(6000), -1L, "never seen");
        m.received(10000);
        eq(m.tick(20000), LinkMonitor.SEND, "alive 10s after ack");
        eq(m.tick(24999), LinkMonitor.NONE, "alive 14.9s");
        eq(m.tick(25000), LinkMonitor.LOST, "lost at 15s");
        eq(m.ageSeconds(25000), 15L, "age");
        m.start(30000);
        eq(m.tick(30000), LinkMonitor.SEND, "restart grace");
        eq(m.tick(44999) == LinkMonitor.LOST, false, "not lost before 15s");
        eq(m.tick(45000), LinkMonitor.LOST, "lost after restart");
        eq(Status.reason(8), "gatt 8 supervision timeout", "code 8");
        eq(Status.gattName(19), "remote terminated", "code 19");
        eq(Status.gattName(22), "local terminated", "code 22");
        eq(Status.gattName(133), "GATT_ERROR", "code 133");
        eq(Status.text("paired", null, 3), "Paired - last seen 3s ago", "text paired");
        eq(Status.text("lost", "heartbeat timeout", 17), "Link lost (heartbeat timeout) - last seen 17s ago", "text lost");
        eq(Status.text("connecting", null, 4), "Connecting", "text connecting");

        ScanScheduler ss = new ScanScheduler();
        eq(ss.beginScan(), true, "scan begins");
        eq(ss.beginScan(), false, "one scan at a time");
        eq(ss.beginConnect(100000), true, "connect allowed, ends scan");
        eq(ss.beginScan(), false, "no scan while connecting");
        eq(ss.beginConnect(200000), false, "one connect at a time");
        long d1 = ss.afterFailure(100500);
        eq(d1, 4500L, "first failure waits out 5s gap");
        ss.endConnect();
        eq(ss.beginConnect(102000), false, "rate limit 5s");
        eq(ss.beginConnect(105000), true, "rate limit passes at 5s");
        ss.endConnect();
        long last = 0;
        for (int i = 0; i < 8; i++) last = ss.afterFailure(500000 + i * 100000);
        eq(last, 30000L, "backoff capped at 30s");
        ss.authed();
        eq(ss.afterFailure(2000000), 2000L, "backoff reset after auth");
        eq(ss.afterEmptyWindow(), 8000L, "duty off 8s");
        eq(ScanScheduler.SCAN_ON_MS, 2000L, "duty on 2s");
        System.out.println("OK " + checks + " checks");
    }

    static final String VECTOR_FRAMES = "8000217b2274223a2263617264222c2274657874 41223a2248656c6c6f20476c617373227d";
}
