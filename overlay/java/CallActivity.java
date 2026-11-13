package com.husntube.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** شاشة اتصال أصلية فوق القفل: واردة (قبول/رفض) ثم مكالمة جارية (اسم + عدّاد + كتم/سماعة/إنهاء) دون فتح التطبيق. */
public class CallActivity extends Activity {
    static volatile CallActivity cur;
    private final Handler hd = new Handler(Looper.getMainLooper());
    private String name, url;
    private TextView status;
    private long t0;
    private boolean muted = false, spk = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cur = this;
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handle(i); }

    private void handle(Intent i) {
        name = i.getStringExtra("name");
        url = i.getStringExtra("url");
        if (name == null || name.isEmpty()) name = (CallService.name != null && !CallService.name.isEmpty()) ? CallService.name : "HusnTube";
        if (i.getBooleanExtra("accept", false) && !CallService.running) startCall();
        if (i.getBooleanExtra("accept", false) || i.getBooleanExtra("live", false) || CallService.running) showLive(); else showIncoming();
    }

    private void startCall() {
        Intent si = new Intent(this, CallService.class).setAction("START").putExtra("url", url).putExtra("name", name);
        try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(si); else startService(si); } catch (Throwable e) { }
    }

    private void svc(String act) {
        try { startService(new Intent(this, CallService.class).setAction(act)); } catch (Throwable e) { }
    }

    private LinearLayout base() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(60, 60, 60, 60);
        root.setBackgroundColor(Color.parseColor("#150d2b"));
        return root;
    }

    private void showIncoming() {
        LinearLayout root = base();
        root.addView(tv("مكالمة واردة", 16, "#b9a8ff", false));
        root.addView(tv(name, 30, "#ffffff", true));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, 160, 0, 0);
        row.addView(btn("رفض", "#e5253b", new View.OnClickListener() {
            @Override public void onClick(View v) {
                sendBroadcast(new Intent(CallActivity.this, CallActionReceiver.class).setAction("DECLINE").setPackage(getPackageName()));
                finish();
            }
        }));
        row.addView(btn("قبول", "#22c55e", new View.OnClickListener() {
            @Override public void onClick(View v) { startCall(); showLive(); }
        }));
        root.addView(row);
        setContentView(root);
    }

    private void showLive() {
        hd.removeCallbacksAndMessages(null);
        LinearLayout root = base();
        root.addView(tv("HusnTube • مكالمة صوتية", 14, "#b9a8ff", false));
        root.addView(tv(name, 32, "#ffffff", true));
        status = tv("جاري الاتصال...", 22, "#4ade80", true);
        root.addView(status);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, 200, 0, 0);
        final TextView mb = btn("كتم", "#475569", null), sb = btn("سماعة", "#475569", null);
        mb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { muted = !muted; svc("MUTE"); mb.setText(muted ? "إلغاء الكتم" : "كتم"); }
        });
        sb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { spk = !spk; svc("SPK"); sb.setText(spk ? "سماعة ✓" : "سماعة"); }
        });
        row.addView(mb);
        row.addView(btn("إنهاء", "#e5253b", new View.OnClickListener() {
            @Override public void onClick(View v) { svc("END"); }
        }));
        row.addView(sb);
        root.addView(row);
        setContentView(root);
        t0 = SystemClock.elapsedRealtime();
        hd.post(new Runnable() {
            @Override public void run() {
                if (CallService.liveAt > 0) {
                    long s = (SystemClock.elapsedRealtime() - CallService.liveAt) / 1000;
                    status.setText(String.format("%02d:%02d", s / 60, s % 60));
                }
                if (!CallService.running && SystemClock.elapsedRealtime() - t0 > 6000) { finish(); return; }
                hd.postDelayed(this, 500);
            }
        });
    }

    private TextView tv(String s, int sp, String col, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(Color.parseColor(col)); t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, 12, 0, 12);
        return t;
    }

    private TextView btn(String s, String col, View.OnClickListener l) {
        TextView t = tv(s, 17, "#ffffff", true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(col)); g.setCornerRadius(120);
        t.setBackground(g);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 160, 1f);
        p.setMargins(10, 0, 10, 0);
        t.setLayoutParams(p);
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    @Override
    protected void onDestroy() {
        hd.removeCallbacksAndMessages(null);
        if (cur == this) cur = null;
        super.onDestroy();
    }
}
