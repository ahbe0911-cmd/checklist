package com.ahbe.alarm

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Dedicated lock-screen-visible alarm UI, independent from checklist note screens. */
class AlarmRingActivity:Activity() {
    private var id:String?=null
    private fun shape(color:Int)=GradientDrawable().apply {
        setColor(color);cornerRadius=32f
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density+0.5).toInt()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(Build.VERSION.SDK_INT>=27) {
            setShowWhenLocked(true);setTurnScreenOn(true)
        }else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        id=intent.getStringExtra("alarm_id")
        showScreen()
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent)
        id=intent.getStringExtra("alarm_id");showScreen()
    }
    private fun showScreen() {
        val item=AlarmStore.get(this,id?:"")?:run {finish();return}
        window.statusBarColor=Color.rgb(19,30,52)
        val base=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
            layoutDirection=View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.rgb(19,30,52));setPadding(dp(25),dp(28),dp(25),dp(28))
        }
        setContentView(base)
        fun text(value:String,size:Float,color:Int=Color.WHITE,bold:Boolean=false)=TextView(this).apply {
            text=value;textSize=size;setTextColor(color);gravity=Gravity.CENTER
            typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val time=DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
            .format(Instant.now())
        base.addView(text("⏰",58f))
        base.addView(text(time,63f,Color.WHITE,true))
        base.addView(text(item.title,25f,Color.WHITE,true).apply {
            setPadding(0,dp(13),0,dp(15))
        })
        base.addView(text(java.text.SimpleDateFormat("EEEE، d MMMM",java.util.Locale("fa"))
            .format(java.util.Date()),17f,Color.rgb(184,199,225)))
        val stop=text("توقف آلارم",19f,Color.WHITE,true).apply {
            background=shape(Color.rgb(199,66,85));setPadding(dp(15),dp(18),dp(15),dp(18))
            setOnClickListener {stopSound();finish()}
        }
        base.addView(stop,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(50)})
        val postpone=text("تعویق " + item.snooze + " دقیقه",18f,Color.rgb(32,48,77),true).apply {
            background=shape(Color.rgb(232,239,249));setPadding(dp(15),dp(18),dp(15),dp(18))
            setOnClickListener {
                AlarmScheduler.snooze(this@AlarmRingActivity,item.id,item.snooze)
                stopSound();finish()
            }
        }
        base.addView(postpone,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
        base.addView(text("گزینه‌های تعویق",13f,Color.rgb(184,199,225)).apply{
            setPadding(0,dp(22),0,dp(8))
        })
        val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        base.addView(row)
        listOf(5,10,15).forEach {minutes->
            row.addView(text("$minutes دقیقه",15f).apply {
                setPadding(dp(14),dp(12),dp(14),dp(12))
                setOnClickListener {
                    AlarmScheduler.snooze(this@AlarmRingActivity,item.id,minutes)
                    stopSound();finish()
                }
            },LinearLayout.LayoutParams(0,-2,1f))
        }
    }
    private fun stopSound(){
        startService(Intent(this,AlarmSoundService::class.java).apply {
            action=AlarmSoundService.STOP
        })
    }
}
