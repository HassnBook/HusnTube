package com.husntube.app;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** شاشة اتصال أصلية خفيفة فوق القفل: قبول يرد بالخلفية بدون فتح التطبيق، رفض يغلق. */
public class CallActivity extends Activity {
    static volatile CallActivity cur;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cur = this;
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        final String name = getIntent().getStringExtra("name"), url = getIntent().getStringExtra("url");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(60, 60, 60, 60);
        root.setBackgroundColor(Color.parseColor("#150d2b"));
        TextView t1 = tv("مكالمة واردة", 16, "#b9a8ff", false), t2 = tv(name == null || name.isEmpty() ? "HusnTube" : name, 30, "#ffffff", true);
        root.addView(t1);
        root.addView(t2);
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
            @Override public void onClick(View v) {
                Intent si = new Intent(CallActivity.this, CallService.class).setAction("START").putExtra("url", url);
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(si); else startService(si);
                } catch (Throwable e) { }
                finish();
            }
        }));
        root.addView(row);
        setContentView(root);
    }

    private TextView tv(String s, int sp, String col, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(Color.parseColor(col)); t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, 12, 0, 12);
        return t;
    }

    private TextView btn(String s, String col, View.OnClickListener l) {
        TextView t = tv(s, 20, "#ffffff", true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(col)); g.setCornerRadius(120);
        t.setBackground(g);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 170, 1f);
        p.setMargins(20, 0, 20, 0);
        t.setLayoutParams(p);
        t.setOnClickListener(l);
        return t;
    }

    @Override
    protected void onDestroy() {
        if (cur == this) cur = null;
        super.onDestroy();
    }
}
