package com.ahbe.alarm

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Live Android capability statuses, not fake app-level permission toggles. */
object AlarmSettingsUi {
    const val NOTIFICATION_PERMISSION = 7025
    const val SETTINGS_LOCATION_PERMISSION = 7026
    const val SETTINGS_MIC_PERMISSION = 7027

    private val ink = Color.rgb(25, 36, 58)
    private val primary = Color.rgb(65, 86, 204)
    private val soft = Color.rgb(237, 241, 255)
    private val muted = Color.rgb(112, 123, 143)
    private fun dp(c: Context, amount: Int) =
        (amount * c.resources.displayMetrics.density + 0.5f).toInt()

    private fun shape(c: Context, color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(c, radius).toFloat()
        }

    private fun text(c: Context, label: String, sp: Float, color: Int = ink,
        bold: Boolean = false) = TextView(c).apply {
        text = label
        textSize = sp
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
        typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD
            else android.graphics.Typeface.DEFAULT
    }

    private fun shell(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setBackgroundColor(Color.rgb(247, 248, 250))
        setPadding(dp(c, 20), dp(c, 18), dp(c, 20), dp(c, 18))
    }

    private fun section(c: Context, parent: LinearLayout): LinearLayout {
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(c, 15), dp(c, 12), dp(c, 15), dp(c, 12))
            background = shape(c, Color.WHITE, 20)
            elevation = dp(c, 3).toFloat()
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(c, 12)
            bottomMargin = dp(c, 8)
            marginStart = dp(c, 3)
            marginEnd = dp(c, 3)
        })
        return card
    }

    private fun pill(c: Context, label: String, action: () -> Unit) =
        text(c, label, 15f, primary, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(c, 52)
            setPadding(dp(c, 13), dp(c, 12), dp(c, 13), dp(c, 12))
            background = shape(c, soft, 14)
            isClickable = true
            setOnClickListener { action() }
        }

    private fun row(c: Context, parent: LinearLayout, label: String, detail: String,
        enabled: Boolean, action: () -> Unit) {
        val outer = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            minHeight = dp(c, 76)
            setPadding(dp(c, 3), dp(c, 8), dp(c, 3), dp(c, 8))
            isClickable = true
            isFocusable = true
            contentDescription = "$label، " + if (enabled) "فعال" else "نیاز به مجوز"
            setOnClickListener { action() }
        }
        parent.addView(outer)
        val labels = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        outer.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        labels.addView(text(c, label, 15f, ink, true))
        labels.addView(text(c, detail, 12f, muted).apply {
            setPadding(0, dp(c, 5), 0, 0)
        })
        val indicator = Switch(c).apply {
            isChecked = enabled
            isClickable = false
            isFocusable = false
            minWidth = dp(c, 60)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList.valueOf(
                if (enabled) primary else Color.rgb(204, 211, 224))
        }
        outer.addView(indicator, LinearLayout.LayoutParams(-2, dp(c, 48)).apply {
            marginStart = dp(c, 8)
        })
    }

    private fun details(a: MainActivity) {
        a.markSettingsRefresh()
        a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + a.packageName)))
    }

    fun show(a: MainActivity) {
        val root = shell(a)
        val bar = LinearLayout(a).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(a, 56)))
        bar.addView(text(a, "تنظیمات آلارم", 24f, ink, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(pill(a, "بازگشت") { a.showHome() })
        val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, dp(a, 8), 0, dp(a, 18))
        }
        scroll.addView(body)
        body.addView(text(a, "مجوزهای برنامه", 18f, ink, true).apply {
            setPadding(0, dp(a, 14), 0, 0)
        })
        val permissions = section(a, body)
        val alarmManager = a.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exact = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        row(a, permissions, "آلارم دقیق",
            "اجرای زنگ در زمان مقرر", exact) {
            if (Build.VERSION.SDK_INT >= 31 && !exact) {
                a.markSettingsRefresh()
                a.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + a.packageName)))
            } else details(a)
        }
        val notificationManager =
            a.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notices = notificationManager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)
        row(a, permissions, "اعلان‌ها", "اعلان زنگ و دکمه توقف", notices) {
            if (Build.VERSION.SDK_INT >= 33 &&
                a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
                a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION)
            } else {
                a.markSettingsRefresh()
                a.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)
                })
            }
        }
        val full = Build.VERSION.SDK_INT < 34 ||
            notificationManager.canUseFullScreenIntent()
        row(a, permissions, "نمایش روی صفحه قفل",
            "باز شدن صفحه زنگ در صورت مجاز بودن", full) {
            if (Build.VERSION.SDK_INT >= 34) {
                a.markSettingsRefresh()
                a.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:" + a.packageName)))
            } else details(a)
        }
        val location = a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        row(a, permissions, "مکان گوشی",
            "برای محاسبه طلوع آفتاب", location) {
            if (!location) a.requestPermissions(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                SETTINGS_LOCATION_PERMISSION)
            else details(a)
        }
        val mic = a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        row(a, permissions, "میکروفون", "برای ضبط صدای اختصاصی آلارم", mic) {
            if (!mic) a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),
                SETTINGS_MIC_PERMISSION)
            else details(a)
        }
        permissions.addView(pill(a, "مدیریت همه مجوزها در تنظیمات گوشی") {
            details(a)
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(a, 8)
        })
        body.addView(text(a, "آزمایش عملکرد", 18f, ink, true).apply {
            setPadding(0, dp(a, 14), 0, 0)
        })
        val testing = section(a, body)
        testing.addView(text(a,
            "یک زنگ آزمایشی واقعی ۱۵ ثانیه بعد اجرا می‌شود. برای بررسی، گوشی را قفل کنید.",
            13f, muted).apply { setPadding(0, dp(a, 5), 0, dp(a, 13)) })
        testing.addView(pill(a, "اجرای تست ۱۵ ثانیه‌ای") {
            if (AlarmTestReceiver.schedule(a)) {
                Toast.makeText(a, "آزمایش زنگ برای ۱۵ ثانیه بعد تنظیم شد",
                    Toast.LENGTH_LONG).show()
            }
        })
        body.addView(text(a, "این سوئیچ‌ها وضعیت واقعی مجوزهای اندروید را نشان می‌دهند؛ " +
            "تغییر دسترسی‌ها در سیستم انجام می‌شود.", 12f, muted).apply {
            setPadding(dp(a, 5), dp(a, 18), dp(a, 5), 0)
        })
        a.setContentView(root)
    }
}
