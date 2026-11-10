# HusnTube — تطبيق أندرويد (Capacitor)

التطبيق يحمّل الموقع https://husntubee1.web.app داخل WebView، فيبقى ويبًا كما هو ويتحدّث بمجرد نشر الموقع.
الجزء الأصلي (Java) يضيف: استقبال FCM والتطبيق مغلق، رنين مكالمة بملء الشاشة مع قبول/رفض، وإشعارات الرسائل.

## البناء بدون تثبيت أي شيء (GitHub Actions)
1. Firebase Console ← Project settings ← Add app ← Android، اسم الحزمة `com.husntube.app` ← نزّل `google-services.json`.
2. أنشئ مستودعًا خاصًا على GitHub وارفع محتوى هذا المجلد (بما فيه المجلد المخفي `.github`).
3. المستودع ← Settings ← Secrets and variables ← Actions ← New secret: الاسم `GOOGLE_SERVICES_JSON` والقيمة نص الملف كاملًا.
4. تبويب Actions ← Build Android APK ← Run workflow. بعد دقائق نزّل `HusnTube-debug-apk` ثم ثبّت `app-debug.apk` على الهاتف.

## الخادم
- في Firebase: فعّل Cloud Messaging API (V1) (غالبًا مفعّل). حساب الخدمة `FB_SA` يكفي.
- الصق `husntube-worker-update.js` الجديد في الـ Worker وانشر `firebase deploy --only hosting,firestore:rules`.

## التجربة
1. ثبّت التطبيق وسجّل الدخول (يطلب إذن الإشعارات) ثم أغلق التطبيق تمامًا.
2. من حساب آخر اتصل بك: يجب أن يرنّ الهاتف ويظهر إشعار المكالمة.
3. أندرويد 14+: امنح «الإشعارات بملء الشاشة» عند ظهور شاشة الإعدادات.
4. شاومي/هواوي/سامسونج: ضع التطبيق «بدون قيود بطارية» وفعّل «التشغيل التلقائي».

## ملاحظات
- «رفض» من الإشعار يوقف الرنين محليًا فقط؛ المتصل يستمر حتى انتهاء المهلة.
- تسجيل الدخول بحساب Google عبر نافذة منبثقة لا يعمل داخل WebView؛ البريد/كلمة المرور يعمل.
- للنشر على Google Play: أنشئ keystore ووقّع `bundleRelease` (لاحقًا).
