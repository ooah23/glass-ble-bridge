package net.n.glassbridge;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Dark Glass-style card view. Tap play/pause, swipe next/prev, up/down volume. */
public class CardActivity extends Activity implements BridgeService.UiCallback {
    private static final int CYAN = 0xFF00E5FF, DIM = 0xFF6F8A90;
    private TextView title, body, status, music;
    private boolean playing;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(40, 24, 40, 24);
        title = text(26, CYAN, Typeface.NORMAL);
        body = text(40, Color.WHITE, Typeface.NORMAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        body.setGravity(Gravity.CENTER_VERTICAL);
        music = text(22, CYAN, Typeface.NORMAL);
        status = text(18, DIM, Typeface.NORMAL);
        root.addView(title); root.addView(body); root.addView(music); root.addView(status);
        setContentView(root);
        BridgeService.start(this);
    }

    private TextView text(int sp, int color, int style) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif-light", style));
        return t;
    }

    @Override protected void onStart() {
        super.onStart();
        BridgeService.setUi(this);
        onState(BridgeService.lastState);
        if (BridgeService.lastCard != null) onCard(BridgeService.lastCard);
        else { String c = BleClient.secretOrCreate(); body.setText(c == null ? "Waiting for phone" : "Pair code\n" + c.substring(0, 4) + " " + c.substring(4) + "\ntype it in the iPhone app"); }
        if (BridgeService.lastNowPlaying != null) onNowPlaying(BridgeService.lastNowPlaying);
    }

    @Override protected void onStop() { BridgeService.setUi(null); super.onStop(); }

    @Override public void onState(String s) { status.setText(s); }

    @Override public void onCard(JSONObject m) {
        title.setText(m.optString("title", ""));
        title.setVisibility(m.optString("title", "").length() == 0 ? View.GONE : View.VISIBLE);
        body.setText(m.optString("text", ""));
    }

    @Override public void onNowPlaying(JSONObject m) {
        playing = "playing".equals(m.optString("state"));
        String t = m.optString("title", "");
        String a = m.optString("artist", "");
        music.setText(t.length() == 0 ? "" : (playing ? "> " : "|| ") + t + (a.length() == 0 ? "" : " - " + a));
    }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER: BridgeService.command(playing ? "pause" : "play"); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: BridgeService.command("next"); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: BridgeService.command("prev"); return true;
            case KeyEvent.KEYCODE_DPAD_UP: BridgeService.command("vol_up"); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: BridgeService.command("vol_down"); return true;
            default: return super.onKeyDown(k, e);
        }
    }
}
