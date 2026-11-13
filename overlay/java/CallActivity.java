package com.husntube.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.content.Context;
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
import android.widget.FrameLayout;
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
        avatar.setImageBitmap(letterBmp());
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
        final FrameLayout mb = cbtn(0, "#475569", null), sb = cbtn(1, "#475569", null);
        mb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { muted = !muted; svc("MUTE"); ((Ico) mb.getTag()).set(muted ? 3 : 0, muted, 0); mb.setBackground(circ(muted ? "#f59e0b" : "#475569")); }
        });
        sb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { spk = !spk; svc("SPK"); sb.setBackground(circ(spk ? "#22c55e" : "#475569")); }
        });
        row.addView(mb);
        row.addView(cbtn(2, "#e5253b", new View.OnClickListener() {
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

    /** أيقونة مرسومة بالمتجهات (ميكروفون / سماعة / إنهاء) داخل دائرة. */
    static class Ico extends View {
        int kind; boolean on; int col;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Ico(Context c, int kind, int col) { super(c); this.kind = kind; this.col = col; }
        void set(int kind, boolean on, int col) { this.kind = kind; this.on = on; this.col = col; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), u = w / 100f;
            p.setColor(Color.WHITE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            p.setStyle(Paint.Style.FILL);
            if (kind == 0 || kind == 3) { // ميكروفون (3 = مكتوم بخط مائل)
                c.drawRoundRect(new RectF(40*u, 22*u, 60*u, 56*u), 10*u, 10*u, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4.5f*u);
                c.drawArc(new RectF(30*u, 36*u, 70*u, 70*u), 0, 180, false, p);
                c.drawLine(50*u, 70*u, 50*u, 80*u, p); c.drawLine(41*u, 80*u, 59*u, 80*u, p);
                if (kind == 3) { p.setStrokeWidth(5.5f*u); c.drawLine(28*u, 24*u, 72*u, 78*u, p); }
            } else if (kind == 1) { // سماعة مكبّر
                Path q = new Path();
                q.moveTo(26*u, 42*u); q.lineTo(38*u, 42*u); q.lineTo(54*u, 28*u); q.lineTo(54*u, 72*u); q.lineTo(38*u, 58*u); q.lineTo(26*u, 58*u); q.close();
                c.drawPath(q, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4.5f*u);
                c.drawArc(new RectF(48*u, 36*u, 68*u, 64*u), -55, 110, false, p);
                c.drawArc(new RectF(44*u, 26*u, 80*u, 74*u), -55, 110, false, p);
            } else { // إنهاء المكالمة: سماعة هاتف مقلوبة
                c.save(); c.rotate(135, 50*u, 50*u);
                Path q = new Path();
                q.moveTo(26*u, 36*u); q.cubicTo(26*u, 30*u, 31*u, 27*u, 36*u, 29*u); q.lineTo(42*u, 32*u);
                q.cubicTo(45*u, 34*u, 45*u, 38*u, 43*u, 41*u); q.lineTo(40*u, 45*u);
                q.cubicTo(46*u, 53*u, 47*u, 54*u, 55*u, 60*u); q.lineTo(59*u, 57*u);
                q.cubicTo(62*u, 55*u, 66*u, 55*u, 68*u, 58*u); q.lineTo(71*u, 64*u);
                q.cubicTo(73*u, 69*u, 70*u, 74*u, 64*u, 74*u); q.cubicTo(42*u, 72*u, 28*u, 58*u, 26*u, 36*u); q.close();
                c.drawPath(q, p); c.restore();
            }
        }
    }

    private FrameLayout cbtn(int kind, String col, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(circ(col));
        f.setElevation(12f);
        Ico ic = new Ico(this, kind, 0);
        f.addView(ic, new FrameLayout.LayoutParams(190, 190));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(190, 190);
        p.setMargins(24, 0, 24, 0);
        f.setLayoutParams(p);
        f.setTag(ic);
        if (l != null) f.setOnClickListener(l);
        return f;
    }

    private Bitmap letterBmp() {
        Bitmap b = Bitmap.createBitmap(340, 340, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(Color.WHITE); pt.setTextSize(150); pt.setTextAlign(Paint.Align.CENTER); pt.setTypeface(Typeface.DEFAULT_BOLD);
        String l = (name == null || name.isEmpty()) ? "H" : name.substring(0, 1);
        c.drawText(l, 170, 170 + 52, pt);
        return b;
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
