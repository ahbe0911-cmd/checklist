# یادداشت‌های من — Native Android

این پروژه شامل نسخه **بومی اندروید با Kotlin** است. طراحی و قابلیت‌های آن از فایل HTML نمونهٔ «یادداشت‌های حرفه‌ای» الگوبرداری شده‌اند، اما هیچ HTML، WebView، React یا Capacitor در ساخت APK جدید استفاده نمی‌شود.

## قابلیت‌ها
- افزودن، تکمیل و حذف کارها
- جست‌وجو و فیلتر «همه / جاری / انجام»
- شمارندهٔ کارهای انجام‌شده و نوار پیشرفت
- تاریخ شمسی فارسی با تقویم داخلی Android ICU
- کارت‌های پاستلی و رابط کاربری فارسی راست‌چین
- ذخیرهٔ محلی و آفلاین با SharedPreferences
- بدون مجوز اینترنت

## ساخت APK با GitHub Actions
از بخش [Actions](../../actions/workflows/build-apk.yml) گردش‌کار **Build Native Android APK** را اجرا کنید یا با push به main آن را اجرا کنید. پس از سبزشدن اجرای workflow، از بخش **Artifacts** فایل `checklist-native-debug-apk` را دانلود کنید؛ پس از بازکردن ZIP، فایل `app-debug.apk` قابل نصب است.

APK نوع debug است و برای نصب آزمایشی است؛ انتشار در Google Play به ساخت و امضای نسخهٔ release نیاز دارد.

## ساخت محلی
به JDK 17، Android SDK 35 و Gradle 8.9 نیاز است. دستور:
`gradle :app:assembleDebug`

کدهای قدیمی Vite/React/Capacitor در مخزن برای حفظ تاریخچه باقی مانده‌اند، اما workflow جدید از آنها استفاده نمی‌کند.
