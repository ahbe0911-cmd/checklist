package com.ahbe.checklist.alarm

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.icu.util.ULocale
import android.icu.util.Calendar as PersianCalendar
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** An actual navigable Persian (Solar Hijri) calendar with day cells.
 * All month lengths and leap years come from Android ICU's Persian calendar.
 */
object PersianCalendarPicker {
    private val months=arrayOf("فروردین","اردیبهشت","خرداد","تیر","مرداد","شهریور",
        "مهر","آبان","آذر","دی","بهمن","اسفند")
    private val weekdays=arrayOf("ش","ی","د","س","چ","پ","ج")
    private val ink=Color.rgb(30,41,59)
    private val blue=Color.rgb(55,99,194)
    private fun dp(c:Context,v:Int)=(v*c.resources.displayMetrics.density+0.5f).toInt()
    private fun font(c:Context):Typeface=runCatching{
        Typeface.createFromAsset(c.assets,"fonts/far_vazir.ttf")
    }.getOrDefault(Typeface.DEFAULT)
    private fun text(c:Context,value:String,size:Float=15f):TextView=TextView(c).apply{
        text=value;textSize=size;gravity=Gravity.CENTER;setTextColor(ink)
        typeface=font(c);setPadding(dp(c,3),dp(c,8),dp(c,3),dp(c,8))
    }
    private fun fill(color:Int,stroke:Int?=null)=GradientDrawable().apply{
        setColor(color);cornerRadius=16f
        if(stroke!=null)setStroke(2,stroke)
    }
    private fun persianCal(epoch:Long):PersianCalendar=
        PersianCalendar.getInstance(ULocale("fa_IR@calendar=persian")).apply{
            timeInMillis=if(epoch>0)epoch else System.currentTimeMillis()
        }
    private fun dayMillis(year:Int,month:Int,day:Int):Long{
        val c=PersianCalendar.getInstance(ULocale("fa_IR@calendar=persian")).apply{
            clear()
            set(PersianCalendar.YEAR,year)
            set(PersianCalendar.MONTH,month)
            set(PersianCalendar.DAY_OF_MONTH,day)
            set(PersianCalendar.HOUR_OF_DAY,12)
        }
        val date=Instant.ofEpochMilli(c.timeInMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    fun view(a:Activity,initial:Long=0L,onPick:(Long)->Unit):View {
        val selected=persianCal(initial)
        val cursor=persianCal(initial).apply{set(PersianCalendar.DAY_OF_MONTH,1)}
        val shell=LinearLayout(a).apply{
            orientation=LinearLayout.VERTICAL
            layoutDirection=View.LAYOUT_DIRECTION_RTL
            setPadding(dp(a,10),dp(a,12),dp(a,10),dp(a,12))
        }
        val heading=LinearLayout(a).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            layoutDirection=View.LAYOUT_DIRECTION_RTL
        }
        val previous=text(a,"ماه قبل",13f).apply{
            setTextColor(blue)
        }
        val title=text(a,"",20f)
        val next=text(a,"ماه بعد",13f).apply{
            setTextColor(blue)
        }
        heading.addView(previous,LinearLayout.LayoutParams(-2,-2))
        heading.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(next,LinearLayout.LayoutParams(-2,-2))
        shell.addView(heading)
        val grid=LinearLayout(a).apply{
            orientation=LinearLayout.VERTICAL
            layoutDirection=View.LAYOUT_DIRECTION_RTL
        }
        shell.addView(grid)
        fun cell(row:LinearLayout,label:String,click:(()->Unit)?=null,
            selectedDay:Boolean=false,today:Boolean=false) {
            val t=text(a,label,15f).apply {
                background=if(selectedDay)fill(blue)
                    else if(today)fill(Color.rgb(235,241,255),blue)
                    else fill(Color.TRANSPARENT)
                if(selectedDay)setTextColor(Color.WHITE)
                if(click!=null)setOnClickListener{click()}
            }
            row.addView(t,LinearLayout.LayoutParams(0,dp(a,46),1f))
        }
        fun repaint() {
            val year=cursor.get(PersianCalendar.YEAR)
            val month=cursor.get(PersianCalendar.MONTH)
            title.text=months[month]+"  "+year.toString()
            grid.removeAllViews()
            val headers=LinearLayout(a).apply{layoutDirection=View.LAYOUT_DIRECTION_RTL}
            grid.addView(headers)
            weekdays.forEach{day->cell(headers,day)}
            val first=persianCal(dayMillis(year,month,1))
            val offset=first.get(PersianCalendar.DAY_OF_WEEK)%7
            val count=first.getActualMaximum(PersianCalendar.DAY_OF_MONTH)
            val today=persianCal(System.currentTimeMillis())
            for(rowIndex in 0..5){
                val row=LinearLayout(a).apply{layoutDirection=View.LAYOUT_DIRECTION_RTL}
                grid.addView(row)
                for(col in 0..6){
                    val number=rowIndex*7+col-offset+1
                    if(number in 1..count){
                        val current=(selected.get(PersianCalendar.YEAR)==year &&
                            selected.get(PersianCalendar.MONTH)==month &&
                            selected.get(PersianCalendar.DAY_OF_MONTH)==number)
                        val isToday=(today.get(PersianCalendar.YEAR)==year &&
                            today.get(PersianCalendar.MONTH)==month &&
                            today.get(PersianCalendar.DAY_OF_MONTH)==number)
                        cell(row,number.toString(),{onPick(dayMillis(year,month,number))},
                            current,isToday)
                    }else cell(row,"")
                }
            }
        }
        // Listeners are attached after the function has been defined.
        previous.setOnClickListener{cursor.add(PersianCalendar.MONTH,-1);repaint()}
        next.setOnClickListener{cursor.add(PersianCalendar.MONTH,1);repaint()}
        repaint()
        return shell
    }
    fun show(a:Activity,initial:Long=0L,onPick:(Long)->Unit){
        lateinit var dialog:AlertDialog
        val calendar=view(a,initial){epoch->dialog.dismiss();onPick(epoch)}
        dialog=AlertDialog.Builder(a).setTitle("انتخاب روز از تقویم شمسی")
            .setView(calendar).setNegativeButton("انصراف",null).create()
        dialog.show()
    }
}
