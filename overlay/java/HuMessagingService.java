package com.husntube.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * يستقبل رسائل FCM (data فقط) حتى والتطبيق مغلق:
 *  type=ring   → إشعار مكالمة بملء الشاشة + رنين متكرر + قبول/رفض
 *  type=cancel → إيقاف الرنين
 *  type=msg|call → إشعار عادي (رسالة / مكالمة فائتة)
 */
public class HuMessagingService extends FirebaseMessagingService {
    public static final int CALL_ID = 4242;
    static final String CH_CALL = "calls_v1", CH_MSG = "msgs_v1";
    static final String HOST = "https://husntubee1.web.app/";

    @Override
    public void onNewToken(String t) { MainActivity.tok = t; }

    @Override
    public void onMessageReceived(RemoteMessage m) {
        Map<String, String> d = m.getData();
        String type = d.get("type");
        if (type == null) return;
        Context c = getApplicationContext();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if ("cancel".equals(type)) { nm.cancel(CALL_ID); return; }
        if (HuApp.fg) return; // التطبيق مفتوح: الويب يتولى العرض والرنين
        ensureChannels(c, nm);
        String url = d.get("url");
        if (url == null || !url.startsWith(HOST)) url = HOST;
        String title = d.get("title"), body = d.get("body");
        if (title == null || title.isEmpty()) title = "HusnTube";
        String cid = d.get("cid");
        if ("ring".equals(type) && cid != null && !cid.isEmpty() && cid.matches("[A-Za-z0-9_-]{1,40}")) url = url + (url.contains("?") ? "&" : "?") + "cid=" + cid;
        if ("ring".equals(type)) showCall(c, nm, title, body == null || body.isEmpty() ? "مكالمة واردة" : body, url);
        else showMsg(c, nm, title, body == null ? "" : body, url, d.get("dm"));
    }

    private static Bitmap logo(Context c) {
        try { return BitmapFactory.decodeResource(c.getResources(), R.drawable.ic_large_husn); } catch (Throwable e) { return null; }
    }

    private static PendingIntent open(Context c, int req, String url, boolean accept) {
        Intent i = new Intent(c, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_url", url).putExtra("accept", accept);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void showCall(Context c, NotificationManager nm, String title, String body, String url) {
        PendingIntent full = open(c, 1, url, false), acc = open(c, 2, url, true);
        PendingIntent dec = PendingIntent.getBroadcast(c, 3, new Intent(c, CallActionReceiver.class).setAction("DECLINE"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_CALL)
                .setSmallIcon(R.drawable.ic_stat_husn).setColor(0xFF7C5CFF).setLargeIcon(logo(c))
                .setContentTitle(title).setContentText(body)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true).setAutoCancel(true)
                .setTimeoutAfter(45000)
                .setContentIntent(full)
                .addAction(android.R.drawable.sym_action_call, "قبول", acc)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "رفض", dec);
        // الشاشة مفتوحة ومفتوحة القفل: إشعار منبثق فقط (رنين الهاتف الأصلي) دون فتح التطبيق تلقائياً.
        // الشاشة مطفأة/مقفلة: شاشة كاملة كما في تطبيقات الاتصال.
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            android.app.KeyguardManager km = (android.app.KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            boolean idle = (pm != null && !pm.isInteractive()) || (km != null && km.isKeyguardLocked());
            if (idle) b.setFullScreenIntent(full, true);
        } catch (Throwable e) { }
        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT; // يكرر الرنين حتى الرد/الإلغاء
        nm.notify(CALL_ID, n);
    }

    private void showMsg(Context c, NotificationManager nm, String title, String body, String url, String dm) {
        int id = dm == null ? 1 : (dm.hashCode() & 0x7fffff) + 10;
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_MSG)
                .setSmallIcon(R.drawable.ic_stat_husn).setColor(0xFF7C5CFF).setLargeIcon(logo(c))
                .setContentTitle(title).setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true).setContentIntent(open(c, id, url, false));
        nm.notify(id, b.build());
    }

    static void ensureChannels(Context c, NotificationManager nm) {
        if (Build.VERSION.SDK_INT < 26) return;
        if (nm.getNotificationChannel(CH_CALL) == null) {
            NotificationChannel ch = new NotificationChannel(CH_CALL, "المكالمات الواردة", NotificationManager.IMPORTANCE_HIGH);
            Uri ring = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ch.setSound(ring, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 800, 500, 800, 500, 800});
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_MSG) == null) {
            NotificationChannel ch = new NotificationChannel(CH_MSG, "الرسائل", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
    }
}
