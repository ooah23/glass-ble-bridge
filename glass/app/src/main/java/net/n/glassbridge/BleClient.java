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
import net.n.glassbridge.core.Auth;
import net.n.glassbridge.core.Backoff;
import net.n.glassbridge.core.Framer;
import net.n.glassbridge.core.Protocol;
import net.n.glassbridge.core.ScanRecord;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.UUID;

/** GATT central: scan, connect, subscribe, reassemble, authenticate, reconnect with backoff. All work runs on the main thread. */
public class BleClient {
    public interface Listener {
        void onState(String state);
        /** Authenticated message from the phone. */
        void onMessage(JSONObject msg);
    }

    private static final long SCAN_WINDOW_MS = 6000, CONNECT_TIMEOUT_MS = 20000, DENIED_RETRY_MS = 60000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Context ctx;
    private final Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Backoff backoff = new Backoff();
    private final LinkedList<byte[]> writeQ = new LinkedList<byte[]>();
    private final Framer.Reassembler reasm = new Framer.Reassembler();
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private boolean scanning, writing, authed, running;

    public BleClient(Context c, Listener l) { ctx = c.getApplicationContext(); listener = l; }

    public void start() {
        if (running) return;
        running = true;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        state("starting");
        scheduleScan(0);
    }

    public void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
        state("stopped");
    }

    public boolean isAuthed() { return authed; }

    /** Send a JSON message to the phone (only meaningful once connected). */
    public void send(JSONObject m) {
        if (rx == null || gatt == null) return;
        for (byte[] f : Framer.chunk(m.toString().getBytes(UTF8))) writeQ.add(f);
        pump();
    }

    // ---- scanning ----

    private void scheduleScan(long delay) {
        h.removeCallbacks(scanRunnable);
        if (running) h.postDelayed(scanRunnable, delay);
    }

    private final Runnable scanRunnable = new Runnable() { public void run() { startScan(); } };
    private final Runnable scanTimeout = new Runnable() {
        public void run() { stopScan(); long d = backoff.next(); state("idle, retry in " + d / 1000 + "s"); scheduleScan(d); }
    };
    private final Runnable connectTimeout = new Runnable() {
        public void run() { state("connect timeout"); closeGatt(); scheduleScan(backoff.next()); }
    };

    private void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            state("bluetooth off");
            scheduleScan(backoff.next());
            return;
        }
        if (scanning) return;
        scanning = true;
        state("scanning");
        adapter.startLeScan(scanCb);
        h.postDelayed(scanTimeout, SCAN_WINDOW_MS);
    }

    private void stopScan() {
        h.removeCallbacks(scanTimeout);
        if (scanning && adapter != null) adapter.stopLeScan(scanCb);
        scanning = false;
    }

    private final BluetoothAdapter.LeScanCallback scanCb = new BluetoothAdapter.LeScanCallback() {
        public void onLeScan(final BluetoothDevice dev, int rssi, final byte[] rec) {
            if (!ScanRecord.parse(rec).matches()) return;
            h.post(new Runnable() { public void run() { if (scanning) connect(dev); } });
        }
    };

    // ---- connection ----

    private void connect(BluetoothDevice dev) {
        stopScan();
        state("connecting");
        h.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        gatt = dev.connectGatt(ctx, false, gattCb);
    }

    private void closeGatt() {
        h.removeCallbacks(connectTimeout);
        authed = false;
        writing = false;
        rx = null;
        writeQ.clear();
        if (gatt != null) { try { gatt.close(); } catch (Exception ignored) {} gatt = null; }
    }

    private void lost(String why) {
        closeGatt();
        long d = backoff.next();
        state(why + ", retry in " + d / 1000 + "s");
        scheduleScan(d);
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(final BluetoothGatt g, int status, final int newState) {
            h.post(new Runnable() { public void run() {
                if (g != gatt) return;
                if (newState == BluetoothGatt.STATE_CONNECTED) { state("discovering"); g.discoverServices(); }
                else if (running) lost("disconnected");
            }});
        }

        @Override public void onServicesDiscovered(final BluetoothGatt g, int status) {
            h.post(new Runnable() { public void run() {
                if (g != gatt) return;
                BluetoothGattService s = g.getService(UUID.fromString(Protocol.SERVICE));
                if (s == null) { g.disconnect(); return; }
                rx = s.getCharacteristic(UUID.fromString(Protocol.RX_CHAR));
                BluetoothGattCharacteristic tx = s.getCharacteristic(UUID.fromString(Protocol.TX_CHAR));
                if (rx == null || tx == null) { g.disconnect(); return; }
                rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                g.setCharacteristicNotification(tx, true);
                BluetoothGattDescriptor d = tx.getDescriptor(UUID.fromString(Protocol.CCCD));
                if (d == null) { g.disconnect(); return; }
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                state("subscribing");
                g.writeDescriptor(d);
            }});
        }

        @Override public void onDescriptorWrite(final BluetoothGatt g, BluetoothGattDescriptor d, final int status) {
            h.post(new Runnable() { public void run() {
                if (g != gatt) return;
                h.removeCallbacks(connectTimeout);
                if (status != BluetoothGatt.GATT_SUCCESS) { g.disconnect(); return; }
                state("authenticating");
                sendRaw("hello", null, null);
            }});
        }

        @Override public void onCharacteristicChanged(final BluetoothGatt g, BluetoothGattCharacteristic c) {
            final byte[] v = c.getValue();
            h.post(new Runnable() { public void run() { if (g == gatt) onFrame(v); } });
        }

        @Override public void onCharacteristicWrite(final BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            h.post(new Runnable() { public void run() { writing = false; pump(); } });
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
            if (secret == null) { state("no secret file"); return; }
            sendRaw("auth", "h", Auth.response(secret, m.optString("n")));
        } else if ("ok".equals(t)) {
            authed = true;
            backoff.reset();
            state("connected");
        } else if ("deny".equals(t)) {
            state("secret rejected");
            closeGatt();
            scheduleScan(DENIED_RETRY_MS);
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

    private void state(String s) { listener.onState(s); }
}
