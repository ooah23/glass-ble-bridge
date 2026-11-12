package net.n.glassbridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import net.n.glassbridge.core.Auth;
import net.n.glassbridge.core.Framer;
import net.n.glassbridge.core.LinkMonitor;
import net.n.glassbridge.core.Protocol;
import net.n.glassbridge.core.ScanRecord;
import net.n.glassbridge.core.ScanScheduler;
import net.n.glassbridge.core.Status;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.UUID;

/**
 * GATT central: scan (2 s on / 8 s off), connect, subscribe, authenticate, heartbeat, recover.
 * One scan or GATT at a time, connectGatt rate-limited (see ScanScheduler). All work runs on the main thread;
 * the scan callback only compares name/uuid. No wake locks.
 */
public class BleClient {
    public interface Listener {
        /** Human-readable status line (e.g. "Paired - last seen 3s ago"). */
        void onState(String state);
        /** Authenticated message from the phone. */
        void onMessage(JSONObject msg);
    }

    private static final String TAG = "GlassBridge";
    private static final long CONNECT_TIMEOUT_MS = 20000, TICK_MS = 1000;
    private static final int MAX_WRITE_Q = 64;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Context ctx;
    private final Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ScanScheduler sched = new ScanScheduler();
    private final LinkMonitor mon = new LinkMonitor();
    private final LinkedList<byte[]> writeQ = new LinkedList<byte[]>();
    private final Framer.Reassembler reasm = new Framer.Reassembler();
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private boolean scanActive, writing, authed, running;
    private volatile boolean matched;
    private String phase = "scanning", reason = "", lastText = "";

    public BleClient(Context c, Listener l) { ctx = c.getApplicationContext(); listener = l; }

    private static long now() { return SystemClock.elapsedRealtime(); }

    public void start() {
        if (running) return;
        running = true;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        Log.d(TAG, "start");
        setPhase("scanning", "");
        h.postDelayed(ticker, TICK_MS);
        scheduleScan(0);
    }

    public void stop() {
        Log.d(TAG, "stop");
        running = false;
        h.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
        sched.endConnect();
        lastText = "";
        listener.onState("Stopped");
    }

    public boolean isAuthed() { return authed; }

    /** Send a JSON message to the phone (only meaningful once connected). */
    public void send(JSONObject m) {
        if (rx == null || gatt == null) return;
        for (byte[] f : Framer.chunk(m.toString().getBytes(UTF8))) { if (writeQ.size() < MAX_WRITE_Q) writeQ.add(f); }
        pump();
    }

    // ---- status ----

    private void setPhase(String p, String why) {
        phase = p;
        reason = why;
        publish();
    }

    private void publish() {
        String t = Status.text(phase, reason, mon.ageSeconds(now()));
        if (!t.equals(lastText)) { lastText = t; listener.onState(t); }
    }

    private final Runnable ticker = new Runnable() { public void run() {
        if (!running) return;
        if (authed) {
            int a = mon.tick(now());
            if (a == LinkMonitor.SEND) {
                Log.d(TAG, "hb send n=" + mon.counter());
                try { send(new JSONObject().put("t", "hb").put("n", mon.counter())); } catch (Exception ignored) {}
            } else if (a == LinkMonitor.LOST) { fail("heartbeat timeout 15s"); }
        }
        publish();
        h.postDelayed(this, TICK_MS);
    }};

    // ---- scanning (2 s on / 8 s off) ----

    private void scheduleScan(long delay) {
        h.removeCallbacks(scanRunnable);
        if (running) h.postDelayed(scanRunnable, delay);
    }

    private final Runnable scanRunnable = new Runnable() { public void run() { startScan(); } };
    private final Runnable scanWindowEnd = new Runnable() {
        public void run() {
            stopScan();
            Log.d(TAG, "scan window ended, no match; next in " + sched.afterEmptyWindow() + " ms");
            scheduleScan(sched.afterEmptyWindow());
        }
    };
    private final Runnable connectTimeout = new Runnable() { public void run() { fail("connect timeout"); } };

    private void startScan() {
        if (!running) return;
        if (adapter == null || !adapter.isEnabled()) {
            Log.d(TAG, "bluetooth off");
            setPhase("lost", "bluetooth off");
            scheduleScan(sched.afterFailure(now()));
            return;
        }
        if (!sched.beginScan()) { Log.d(TAG, "scan skipped, busy"); return; }
        matched = false;
        scanActive = true;
        if (!"lost".equals(phase)) setPhase("scanning", "");
        Log.d(TAG, "scan start (2 s window)");
        if (!adapter.startLeScan(scanCb)) Log.d(TAG, "startLeScan returned false");
        h.postDelayed(scanWindowEnd, ScanScheduler.SCAN_ON_MS);
    }

    private void stopScan() {
        h.removeCallbacks(scanWindowEnd);
        if (scanActive && adapter != null) { try { adapter.stopLeScan(scanCb); } catch (Exception ignored) {} }
        scanActive = false;
        sched.endScan();
    }

    /** Runs on a binder thread for every advertisement: compare name/uuid only, hand the first match to the main thread. */
    private final BluetoothAdapter.LeScanCallback scanCb = new BluetoothAdapter.LeScanCallback() {
        public void onLeScan(final BluetoothDevice dev, final int rssi, byte[] rec) {
            if (matched || !ScanRecord.parse(rec).matches()) return;
            matched = true;
            h.post(new Runnable() { public void run() {
                Log.d(TAG, "scan match " + dev.getAddress() + " rssi " + rssi);
                if (scanActive) connect(dev);
            }});
        }
    };

    // ---- connection ----

    private void connect(BluetoothDevice dev) {
        stopScan();
        if (gatt != null) closeGatt();
        if (!sched.beginConnect(now())) {
            Log.d(TAG, "connect rate-limited, retry in " + ScanScheduler.MIN_CONNECT_GAP_MS + " ms");
            scheduleScan(ScanScheduler.MIN_CONNECT_GAP_MS);
            return;
        }
        Log.d(TAG, "connect " + dev.getAddress());
        setPhase("connecting", "");
        h.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        gatt = dev.connectGatt(ctx, false, gattCb);
    }

    /** disconnect, drop the GATT cache via reflection refresh(), close, null the reference. */
    private void closeGatt() {
        h.removeCallbacks(connectTimeout);
        authed = false;
        writing = false;
        rx = null;
        writeQ.clear();
        BluetoothGatt g = gatt;
        gatt = null;
        if (g == null) return;
        try { g.disconnect(); } catch (Exception e) { Log.d(TAG, "disconnect threw " + e); }
        try {
            Method m = g.getClass().getMethod("refresh");
            Object r = m.invoke(g);
            Log.d(TAG, "gatt refresh -> " + r);
        } catch (Exception e) { Log.d(TAG, "gatt refresh unavailable " + e); }
        try { g.close(); } catch (Exception e) { Log.d(TAG, "close threw " + e); }
    }

    /** Any failure or disconnect: tear everything down, show the reason, rescan with backoff 2/4/8..30 s. */
    private void fail(String why) {
        if (!running) return;
        Log.d(TAG, "link lost: " + why);
        stopScan();
        closeGatt();
        sched.endConnect();
        setPhase("lost", why);
        long d = sched.afterFailure(now());
        Log.d(TAG, "rescan in " + d + " ms");
        scheduleScan(d);
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(final BluetoothGatt g, final int status, final int newState) {
            h.post(new Runnable() { public void run() {
                Log.d(TAG, "onConnectionStateChange status=" + status + " (" + Status.gattName(status) + ") newState=" + newState + (g == gatt ? "" : " [stale]"));
                if (g != gatt) return;
                if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "discoverServices");
                    if (!g.discoverServices()) fail("discoverServices failed to start");
                } else if (running) fail(Status.reason(status) + ", state " + newState);
            }});
        }

        @Override public void onServicesDiscovered(final BluetoothGatt g, final int status) {
            h.post(new Runnable() { public void run() {
                Log.d(TAG, "onServicesDiscovered status=" + status);
                if (g != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("service discovery " + Status.reason(status)); return; }
                BluetoothGattService s = g.getService(UUID.fromString(Protocol.SERVICE));
                if (s == null) { fail("service missing"); return; }
                rx = s.getCharacteristic(UUID.fromString(Protocol.RX_CHAR));
                BluetoothGattCharacteristic tx = s.getCharacteristic(UUID.fromString(Protocol.TX_CHAR));
                if (rx == null || tx == null) { fail("characteristic missing"); return; }
                rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                g.setCharacteristicNotification(tx, true);
                BluetoothGattDescriptor d = tx.getDescriptor(UUID.fromString(Protocol.CCCD));
                if (d == null) { fail("CCCD missing"); return; }
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                Log.d(TAG, "CCCD write");
                if (!g.writeDescriptor(d)) fail("CCCD write failed to start");
            }});
        }

        @Override public void onDescriptorWrite(final BluetoothGatt g, BluetoothGattDescriptor d, final int status) {
            h.post(new Runnable() { public void run() {
                Log.d(TAG, "onDescriptorWrite status=" + status + " (" + Status.gattName(status) + ")");
                if (g != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("CCCD " + Status.reason(status)); return; }
                setPhase("authenticating", "");
                sendRaw("hello", null, null);
            }});
        }

        @Override public void onCharacteristicChanged(final BluetoothGatt g, BluetoothGattCharacteristic c) {
            final byte[] v = c.getValue();
            h.post(new Runnable() { public void run() { if (g == gatt) onFrame(v); } });
        }

        @Override public void onCharacteristicWrite(final BluetoothGatt g, BluetoothGattCharacteristic c, final int status) {
            h.post(new Runnable() { public void run() {
                if (g != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) Log.d(TAG, "write status=" + status);
                writing = false;
                pump();
            }});
        }
    };

    // ---- messages ----

    private void onFrame(byte[] frame) {
        byte[] json = reasm.feed(frame);
        if (json == null) return;
        JSONObject m;
        try { m = new JSONObject(new String(json, UTF8)); } catch (Exception e) { return; }
        String t = m.optString("t");
        if ("chal".equals(t)) {
            String secret = readSecret();
            if (secret == null) { Log.d(TAG, "auth: no secret file"); fail("no secret file"); return; }
            Log.d(TAG, "auth: answering challenge");
            sendRaw("auth", "h", Auth.response(secret, m.optString("n")));
        } else if ("ok".equals(t)) {
            Log.d(TAG, "auth result: ok");
            h.removeCallbacks(connectTimeout);
            authed = true;
            sched.authed();
            long n = now();
            mon.start(n);
            mon.received(n);
            setPhase("paired", "");
        } else if ("deny".equals(t)) {
            Log.d(TAG, "auth result: deny");
            stopScan();
            closeGatt();
            sched.endConnect();
            setPhase("lost", "secret rejected");
            scheduleScan(30000);
        } else if (authed && "hb".equals(t)) {
            mon.received(now());
            Log.d(TAG, "hb rx n=" + m.optInt("n"));
            try { send(new JSONObject().put("t", "hback").put("n", m.optInt("n"))); } catch (Exception ignored) {}
            publish();
        } else if (authed && "hback".equals(t)) {
            mon.received(now());
            Log.d(TAG, "hback rx n=" + m.optInt("n"));
            publish();
        } else if (authed) {
            listener.onMessage(m);
        }
    }

    private void sendRaw(String t, String k, String v) {
        try {
            JSONObject m = new JSONObject().put("t", t);
            if (k != null) m.put(k, v);
            send(m);
        } catch (Exception ignored) {}
    }

    private void pump() {
        if (writing || writeQ.isEmpty() || gatt == null || rx == null) return;
        rx.setValue(writeQ.removeFirst());
        writing = true;
        if (!gatt.writeCharacteristic(rx)) writing = false;
    }

    /** Secret lives on the Glass at /sdcard/glass-bridge/secret (pushed by adb), never in the APK. */
    private String readSecret() { return secretOrCreate(); }

    /** Pairing code: if no secret exists yet the Glass makes a random 8-char one, stores it and shows it on its card so N types it into the iPhone app (no secret ever travels through chat). */
    static String secretOrCreate() {
        String s = readFile();
        if (s != null) return s;
        try {
            String al = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; java.security.SecureRandom r = new java.security.SecureRandom();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(al.charAt(r.nextInt(al.length())));
            File d = new File(Environment.getExternalStorageDirectory(), "glass-bridge"); d.mkdirs();
            java.io.FileWriter w = new java.io.FileWriter(new File(d, "secret")); w.write(b.toString()); w.close();
            return b.toString();
        } catch (Exception e) { return null; }
    }

    private static String readFile() {
        try {
            File f = new File(Environment.getExternalStorageDirectory(), "glass-bridge/secret");
            BufferedReader r = new BufferedReader(new FileReader(f));
            try { String s = r.readLine(); return s == null || s.trim().length() == 0 ? null : s.trim(); }
            finally { r.close(); }
        } catch (Exception e) { return null; }
    }
}
