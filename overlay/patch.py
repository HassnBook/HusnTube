#!/usr/bin/env python3
"""يُشغَّل بعد `npx cap add android`: ينسخ كود جافا ويعدّل AndroidManifest وملفات gradle."""
import os, re, shutil, sys
A = "android"; M = A + "/app/src/main/AndroidManifest.xml"; B = A + "/app/build.gradle"
J = A + "/app/src/main/java/com/husntube/app"
if not os.path.isdir(A): sys.exit("android/ غير موجود: شغّل npx cap add android أولاً")
os.makedirs(J, exist_ok=True)
for f in os.listdir("overlay/java"): shutil.copy("overlay/java/" + f, J + "/" + f)

m = open(M, encoding="utf-8").read()
if "HuMessagingService" not in m:
    if "xmlns:tools" not in m:
        m = m.replace("<manifest ", '<manifest xmlns:tools="http://schemas.android.com/tools" ', 1)
    perms = """
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-feature android:name="android.hardware.microphone" android:required="false" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />
"""
    m = m.replace("<application", perms + "\n    <application", 1)
    comps = """
        <service android:name="com.capacitorjs.plugins.pushnotifications.MessagingService" tools:node="remove" />
        <service android:name=".HuMessagingService" android:exported="false">
            <intent-filter android:priority="100">
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>
        <receiver android:name=".CallActionReceiver" android:exported="false" />
        <service android:name=".CallService" android:exported="false" android:foregroundServiceType="microphone" />
"""
    m = m.replace("</application>", comps + "    </application>", 1)
    m = m.replace('android:name=".MainActivity"', 'android:name=".MainActivity"\n            android:showWhenLocked="true"\n            android:turnScreenOn="true"', 1)
    open(M, "w", encoding="utf-8").write(m)

g = open(B, encoding="utf-8").read()
if "firebase-messaging" not in g:
    g += "\n\ndependencies {\n    implementation 'com.google.firebase:firebase-messaging:23.4.1'\n}\n"
    open(B, "w", encoding="utf-8").write(g)
# أيقونة التطبيق (adaptive) بدل أيقونة Capacitor
R = A + "/app/src/main/res"
shutil.copytree("overlay/res", R, dirs_exist_ok=True)
for d in ("drawable-v24", "drawable"):
    f = R + "/" + d + "/ic_launcher_foreground.xml"
    if os.path.exists(f): os.remove(f)
# تحقق صارم
m2 = open(M, encoding="utf-8").read()
for need in ("CallService", "FOREGROUND_SERVICE_MICROPHONE", "HuMessagingService", "RECORD_AUDIO", "POST_NOTIFICATIONS", "USE_FULL_SCREEN_INTENT"):
    if need not in m2: sys.exit("فشل التعديل: " + need + " غير موجود في AndroidManifest.xml")
print("patched OK")
