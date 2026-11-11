package net.n.glassbridge;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import org.json.JSONObject;

/** Keeps the BLE client alive while the activity is closed. Static hooks keep this tiny. */
public class BridgeService extends Service implements BleClient.Listener {
    public interface UiCallback { void onState(String s); void onCard(JSONObject m); void onNowPlaying(JSONObject m); }

    private static BridgeService instance;
    private static UiCallback ui;
    public static String lastState = "stopped";
    public static JSONObject lastCard, lastNowPlaying;
    private BleClient ble;

    public static void start(Context c) { c.startService(new Intent(c, BridgeService.class)); }
    public static void setUi(UiCallback cb) { ui = cb; }

    public static void command(String cmd) {
        if (instance == null || instance.ble == null || !instance.ble.isAuthed()) return;
        try { instance.ble.send(new JSONObject().put("t", "cmd").put("c", cmd)); } catch (Exception ignored) {}
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        instance = this;
        if (ble == null) { ble = new BleClient(this, this); ble.start(); }
        return START_STICKY;
    }

    @Override public void onDestroy() { if (ble != null) ble.stop(); ble = null; instance = null; }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onState(String s) { lastState = s; if (ui != null) ui.onState(s); }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        if ("card".equals(t)) {
            lastCard = m;
            if (ui != null) ui.onCard(m);
            else {
                Intent i = new Intent(this, CardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
            }
        } else if ("now_playing".equals(t)) {
            lastNowPlaying = m;
            if (ui != null) ui.onNowPlaying(m);
        }
    }
}
