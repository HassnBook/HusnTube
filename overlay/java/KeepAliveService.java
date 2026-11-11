package com.husntube.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/** خدمة أمامية خفيفة تبقي التطبيق حيّاً في الخلفية ليكون جاهزاً عند وصول مكالمة. */
public class KeepAliveService extends Service {
    static final int ID = 4243;
    static final String CH = "keep_v1";

    public static boolean on(Context c) {
        return c.getSharedPreferences("hu", Context.MODE_PRIVATE).getBoolean("keep", true);
    }

    public static void set(Context c, boolean v) {
        c.getSharedPreferences("hu", Context.MODE_PRIVATE).edit().putBoolean("keep", v).apply();
        if (v) start(c);
        else {
            try { c.stopService(new Intent(c, KeepAliveService.class)); } catch (Throwable e) { }
        }
    }

    public static void start(Context c) {
        try {
            if (!on(c)) return;
            Intent i = new Intent(c, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable e) { }
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                NotificationChannel ch = new NotificationChannel(CH, "البقاء متصلاً", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 9, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new NotificationCompat.Builder(this, CH)
                    .setSmallIcon(R.drawable.ic_stat_husn)
                    .setContentTitle("HusnTube")
                    .setContentText("متصل لاستقبال المكالمات والرسائل")
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setContentIntent(pi)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(ID, n);
        } catch (Throwable e) {
            stopSelf();
        }
        return START_STICKY;
    }
}
