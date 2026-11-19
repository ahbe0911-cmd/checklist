# Novi — standalone Android APK

This branch builds **Novi / یادداشت‌های من** as a separately installable Android application.

- Application ID: `com.ahbe.novi`
- The original `main` branch and combined application are untouched.
- Android alarm module source code and alarm screens, receivers, service and permissions are excluded from the standalone branch.
- Existing native note features, voice recording, cancel/discard, playback, confirmed voice deletion, images, note reminders/calendar and backup/restore remain.
- No network permission is requested.
- Private storage is isolated from the original `com.ahbe.checklist` application. To transfer old data, export a backup from the original app and restore it in Novi.

## Install
GitHub Actions > **Build Novi Standalone APK** > most recent successful run > artifact `novi-standalone-debug-apk` > unzip > `app-debug.apk`.

To build locally: Android SDK 35, JDK 17, Gradle 8.9, and the same Persian font assets downloaded by the workflow; run `gradle :app:assembleDebug`.
