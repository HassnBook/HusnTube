package com.husntube.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Base64;
import android.widget.ImageView;
import android.view.ViewOutlineProvider;
import android.graphics.Outline;
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
    private ImageView avatar;
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
        root.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.setPadding(60, 220, 60, 100);
        root.addView(tv("HusnTube • مكالمة صوتية", 14, "#b9a8ff", false));
        avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackground(circ("#6d4aff"));
        avatar.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        avatar.setClipToOutline(true);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(340, 340);
        ap.setMargins(0, 60, 0, 40);
        avatar.setLayoutParams(ap);
        root.addView(avatar);
        loadPhoto();
        root.addView(tv(name, 32, "#ffffff", true));
        status = tv("جاري الاتصال...", 22, "#4ade80", true);
        root.addView(status);
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f));
        root.addView(sp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        final TextView mb = cbtn("🎤", "#475569", null), sb = cbtn("🔊", "#475569", null);
        mb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { muted = !muted; svc("MUTE"); mb.setText(muted ? "🔇" : "🎤"); mb.setBackground(circ(muted ? "#f59e0b" : "#475569")); }
        });
        sb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { spk = !spk; svc("SPK"); sb.setBackground(circ(spk ? "#22c55e" : "#475569")); }
        });
        row.addView(mb);
        row.addView(cbtn("📞", "#e5253b", new View.OnClickListener() {
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

    private GradientDrawable circ(String col) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL); g.setColor(Color.parseColor(col));
        return g;
    }

    private TextView cbtn(String s, String col, View.OnClickListener l) {
        TextView t = tv(s, 28, "#ffffff", true);
        t.setBackground(circ(col));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(190, 190);
        p.setMargins(24, 0, 24, 0);
        t.setLayoutParams(p);
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    /** يحمّل صورة المتصل (data: أو http) من CallService.photo ويعرضها داخل الدائرة. */
    void loadPhoto() {
        final String p = CallService.photo;
        if (avatar == null || p == null || p.isEmpty()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap bm = null;
                try {
                    if (p.startsWith("data:")) {
                        byte[] d = Base64.decode(p.substring(p.indexOf(',') + 1), Base64.DEFAULT);
                        bm = BitmapFactory.decodeByteArray(d, 0, d.length);
                    } else if (p.startsWith("http")) {
                        java.io.InputStream in = new java.net.URL(p).openStream();
                        bm = BitmapFactory.decodeStream(in); in.close();
                    }
                } catch (Throwable e) { }
                final Bitmap b = bm;
                if (b != null) hd.post(new Runnable() { @Override public void run() { if (avatar != null) avatar.setImageBitmap(b); } });
            }
        }).start();
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
