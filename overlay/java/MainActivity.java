package com.husntube.app;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BridgeActivity {
    public static volatile String tok = "";
    private String pending = null;
    private final Handler hd = new Handler(Looper.getMainLooper());
    private final Runnable beat = new Runnable() {
        @Override public void run() { pushState(); hd.postDelayed(this, 3000); }
    };

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        handle(getIntent());
        applyPending(); // يستبدل التحميل الأول مباشرة بدل تحميلين متتاليين
        askPermissions();
        fetchToken();
        askFullScreen();
        askBattery();
    }

    @Override
    public void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
        applyPending();
    }

    @Override public void onStart() { super.onStart(); HuApp.fg = true; hd.post(beat); fetchToken(); }
    @Override public void onStop() { super.onStop(); HuApp.fg = false; hd.removeCallbacks(beat); }
    @Override public void onResume() { super.onResume(); applyPending(); }

    private boolean has(String p) { return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED; }

    /** صلاحيات أندرويد الفعلية: إشعارات (13+) + ميكروفون + كاميرا. */
    private void askPermissions() {
        try {
            List<String> need = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) need.add(Manifest.permission.POST_NOTIFICATIONS);
            if (!has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
            if (!has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
            if (!need.isEmpty()) ActivityCompat.requestPermissions(this, need.toArray(new String[0]), 77);
        } catch (Exception e) { }
    }

    private void fetchToken() {
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(t -> {
                if (t.isSuccessful() && t.getResult() != null) tok = t.getResult();
            });
        } catch (Throwable e) { }
    }

    private boolean fsiOk() {
        try {
            if (Build.VERSION.SDK_INT < 34) return true;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.canUseFullScreenIntent();
        } catch (Throwable e) { return true; }
    }

    /** يحقن الحالة في الصفحة (window.__hu) دون الاعتماد على جسر Capacitor. */
    private void pushState() {
        try {
            if (getBridge() == null || getBridge().getWebView() == null) return;
            boolean notif = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS);
            String js = "window.__hu={v:'dm',tok:'" + (tok == null ? "" : tok.replace("'", "")) + "',mic:" + has(Manifest.permission.RECORD_AUDIO)
                    + ",cam:" + has(Manifest.permission.CAMERA) + ",notif:" + notif + ",fsi:" + fsiOk() + "};";
            getBridge().getWebView().evaluateJavascript(js, null);
        } catch (Throwable e) { }
    }

    private void handle(Intent i) {
        if (i == null) return;
        String u = i.getStringExtra("open_url");
        if (u != null && u.startsWith(HuMessagingService.HOST)) {
            if (i.getBooleanExtra("accept", false)) u = u + (u.contains("?") ? "&" : "?") + "acc=1";
            pending = u;
            i.removeExtra("accept");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(HuMessagingService.CALL_ID);
            i.removeExtra("open_url");
        }
    }

    private void applyPending() {
        if (pending != null && getBridge() != null && getBridge().getWebView() != null) {
            String u = pending; pending = null;
            getBridge().getWebView().loadUrl(u);
        }
    }

    /** استثناء من توفير البطارية (مرة واحدة): يمنع النظام من تجميد التطبيق فتصل الإشعارات والمكالمات. */
    private void askBattery() {
        try {
            if (Build.VERSION.SDK_INT < 23) return;
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            SharedPreferences p = getSharedPreferences("hu", MODE_PRIVATE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName()) && !p.getBoolean("bat", false)) {
                p.edit().putBoolean("bat", true).apply();
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception e) { }
    }

    /** أندرويد 14+: إذن الإشعار بملء الشاشة يُمنح من الإعدادات، نوجّه المستخدم مرة واحدة. */
    private void askFullScreen() {
        try {
            if (Build.VERSION.SDK_INT < 34) return;
            SharedPreferences p = getSharedPreferences("hu", MODE_PRIVATE);
            if (!fsiOk() && !p.getBoolean("fsi", false)) {
                p.edit().putBoolean("fsi", true).apply();
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception e) { }
    }
}
