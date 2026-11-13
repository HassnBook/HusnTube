package com.husntube.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.app.NotificationCompat;

/** يرد على المكالمة في الخلفية بدون فتح التطبيق: WebView مخفي يحمّل الموقع ويقبل المكالمة تلقائياً. */
public class CallService extends Service {
    static final String CH = "call_active_v1";
    static final int NID = 4243;
    static volatile boolean running = false;
    static volatile String name = "";
    static volatile long liveAt = 0;
    private WebView wv;
    private final Handler hd = new Handler(Looper.getMainLooper());
    private AudioManager am;
    private int oldMode = AudioManager.MODE_NORMAL;
    private boolean live = false;

    @Override public IBinder onBind(Intent i) { return null; }

    private Notification note(String text) {
        Intent endI = new Intent(this, CallService.class).setAction("END");
        PendingIntent end = PendingIntent.getService(this, 9, endI, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent op = new Intent(this, CallActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("live", true).putExtra("name", name);
        PendingIntent open = PendingIntent.getActivity(this, 10, op, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat_husn).setColor(0xFF7C5CFF)
                .setContentTitle(name == null || name.isEmpty() ? "HusnTube" : name).setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true)
                .setUsesChronometer(live).setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إنهاء", end).build();
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String act = i == null ? null : i.getAction();
        if ("END".equals(act)) { endCall(); return START_NOT_STICKY; }
        if ("MUTE".equals(act)) { try { if (wv != null) wv.evaluateJavascript("window.__huMute&&window.__huMute()", null); } catch (Throwable e) { } return START_NOT_STICKY; }
        if ("SPK".equals(act)) { try { if (am != null) am.setSpeakerphoneOn(!am.isSpeakerphoneOn()); } catch (Throwable e) { } return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        String url = i == null ? null : i.getStringExtra("url");
        if (url == null || !url.startsWith(HuMessagingService.HOST)) { stopSelf(); return START_NOT_STICKY; }
        running = true;
        liveAt = 0;
        String nmx = i.getStringExtra("name");
        name = nmx == null ? "" : nmx;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(new NotificationChannel(CH, "مكالمة جارية", NotificationManager.IMPORTANCE_LOW));
        if (nm != null) nm.cancel(HuMessagingService.CALL_ID);
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, note("جاري الاتصال..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NID, note("جاري الاتصال..."));
        } catch (Throwable e) { running = false; stopSelf(); return START_NOT_STICKY; }
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        try { if (am != null) { oldMode = am.getMode(); am.setMode(AudioManager.MODE_IN_COMMUNICATION); } } catch (Throwable e) { }
        try {
            wv = new WebView(getApplicationContext());
            WebSettings s = wv.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            wv.addJavascriptInterface(new Object() {
                @JavascriptInterface public void live() { hd.post(() -> { live = true; liveAt = android.os.SystemClock.elapsedRealtime(); refresh("مكالمة جارية"); }); }
                @JavascriptInterface public void ended() { hd.post(() -> stopSelfSafe()); }
            }, "HuNative");
            wv.setWebViewClient(new WebViewClient());
            wv.setWebChromeClient(new WebChromeClient() {
                @Override public void onPermissionRequest(final PermissionRequest r) { hd.post(() -> r.grant(r.getResources())); }
            });
            wv.loadUrl(url + (url.contains("?") ? "&" : "?") + "hl=1&acc=1");
        } catch (Throwable e) { stopSelfSafe(); return START_NOT_STICKY; }
        // إن لم تتصل المكالمة خلال دقيقة نوقف الخدمة
        hd.postDelayed(() -> { if (!live) stopSelfSafe(); }, 60000);
        return START_NOT_STICKY;
    }

    private void refresh(String t) {
        try { NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE); if (nm != null) nm.notify(NID, note(t)); } catch (Throwable e) { }
    }

    private void endCall() {
        try { if (wv != null) wv.evaluateJavascript("window.__huEnd&&window.__huEnd()", null); } catch (Throwable e) { }
        hd.postDelayed(this::stopSelfSafe, 1200);
    }

    private void stopSelfSafe() {
        if (!running) return;
        running = false;
        try { stopForeground(true); } catch (Throwable e) { }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        liveAt = 0;
        try { CallActivity a = CallActivity.cur; if (a != null) a.finish(); } catch (Throwable e) { }
        hd.removeCallbacksAndMessages(null);
        try { if (am != null) am.setMode(oldMode); } catch (Throwable e) { }
        try { if (wv != null) { wv.loadUrl("about:blank"); wv.destroy(); wv = null; } } catch (Throwable e) { }
        super.onDestroy();
    }
}
