package com.ahbe.alarm

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast

/** Standalone one-off test; never enters the regular recurrence scheduler. */
class AlarmTestReceiver : BroadcastReceiver() {
    companion object {
        const val ID = "standalone_alarm_test_15s"
        private const val ACTION = "com.ahbe.alarm.TEST_RING"

        fun schedule(a: Activity): Boolean {
            val mgr = a.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= 31 && !mgr.canScheduleExactAlarms()) {
                Toast.makeText(a, "ابتدا مجوز آلارم دقیق را فعال کنید",
                    Toast.LENGTH_LONG).show()
                return false
            }
            val list = AlarmStore.read(a).filterNot { it.id == ID }.toMutableList()
            // Disabled prevents backup/boot recovery from scheduling a second test.
            list.add(AlarmItem(id = ID, title = "تست زنگ", enabled = false,
                repeat = 0, sound = "preset:0"))
            AlarmStore.save(a, list)
            val pending = PendingIntent.getBroadcast(a, 9215,
                Intent(a, AlarmTestReceiver::class.java).apply { action = ACTION },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return try {
                mgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 15000L, pending)
                true
            } catch (_: SecurityException) {
                Toast.makeText(a, "اجازه اجرای زنگ دقیق وجود ندارد",
                    Toast.LENGTH_LONG).show()
                false
            }
        }
    }

    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val item = AlarmStore.get(c, ID) ?: return
        val start = Intent(c, AlarmSoundService::class.java).apply {
            action = AlarmSoundService.START
            putExtra("alarm_id", item.id)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(start)
            else c.startService(start)
        } catch (_: Exception) {
            AlarmSoundService.notifyOnly(c, item)
        }
    }
}
