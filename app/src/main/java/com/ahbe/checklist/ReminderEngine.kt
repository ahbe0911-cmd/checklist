package com.ahbe.checklist

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray

object ReminderEngine {
    private const val CHANNEL_ID = "notes_reminders_v1"
    const val ACTION_REMIND = "com.ahbe.checklist.REMIND"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "یادآوری یادداشت‌ها",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "اعلان‌های یادداشت‌ها در تاریخ و ساعت انتخاب‌شده"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun intent(context: Context, id: String, text: String = ""): Intent =
        Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_REMIND
            data = Uri.parse("checklist://note/" + Uri.encode(id))
            putExtra("id", id)
            putExtra("text", text)
        }

    private fun pending(context: Context, id: String, text: String, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, 0, intent(context, id, text),
            flags or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(context: Context, id: String, text: String, whenMillis: Long) {
        if (whenMillis <= System.currentTimeMillis()) return
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarm = pending(context, id, text, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, alarm)
            } else {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, alarm)
            }
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, alarm)
        }
    }

    fun cancel(context: Context, id: String) {
        val alarm = pending(context, id, "", PendingIntent.FLAG_NO_CREATE) ?: return
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.cancel(alarm)
        alarm.cancel()
    }

    fun restore(context: Context) {
        val prefs = context.getSharedPreferences("checklist_native", Context.MODE_PRIVATE)
        val json = runCatching {
            JSONArray(prefs.getString("optimized_notes", "[]") ?: "[]")
        }.getOrNull() ?: return
        for (i in 0 until json.length()) {
            val item = json.optJSONObject(i) ?: continue
            if (item.optBoolean("completed", false)) continue
            val scheduled = item.optLong("reminderAt", 0L)
            if (scheduled <= System.currentTimeMillis()) continue
            schedule(context, item.optString("id"), item.optString("text"), scheduled)
        }
    }

    fun post(context: Context, id: String, text: String) {
        createChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val open = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(context, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("یادآوری یادداشت")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        runCatching { manager.notify(id.hashCode() and Int.MAX_VALUE, notification) }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderEngine.ACTION_REMIND) return
        ReminderEngine.post(context, intent.getStringExtra("id") ?: "",
            intent.getStringExtra("text") ?: "")
    }
}

class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED ||
            intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            ReminderEngine.restore(context)
        }
    }
}
