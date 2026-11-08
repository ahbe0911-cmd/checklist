package com.ahbe.checklist.alarm

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.time.*
import android.icu.util.ULocale
import android.icu.util.Calendar as IcuCalendar
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import java.util.UUID
import kotlin.math.*

/** All alarm UI lives here: only the toolbar button calls AlarmUi.show(activity). */
object AlarmUi {
    const val PICK_AUDIO=7022
    const val LOCATION_PERMISSION=7023
    const val MIC_PERMISSION=7024
    private val navy=Color.rgb(30,41,59)
    private val blue=Color.rgb(73,106,194)
    private val muted=Color.rgb(101,116,139)
    private val light=Color.rgb(246,248,252)
    private val border=Color.rgb(223,229,240)
    private val days=arrayOf("ش","ی","د","س","چ","پ","ج")
    private var chooser:( (String)->Unit )?=null
    private var locateAfterPermission:(()->Unit)?=null
    private var recordAfterPermission:(()->Unit)?=null
    private var activeDialog:Dialog?=null
    private var preview:MediaPlayer?=null
    private var recorder:MediaRecorder?=null
    private var recordingFile:File?=null
    private var waveformHandler:Handler?=null
    private var waveformTick:Runnable?=null

    private fun dp(c:Context,v:Int)=(v*c.resources.displayMetrics.density+0.5f).toInt()
    private fun shape(c:Context,color:Int,r:Int=14,stroke:Int?=null)=GradientDrawable().apply{
        setColor(color);cornerRadius=dp(c,r).toFloat()
        if(stroke!=null)setStroke(dp(c,1),stroke)
    }
    private fun vazir(c:Context,bold:Boolean=false):Typeface = runCatching {
        Typeface.createFromAsset(c.assets,"fonts/far_vazir.ttf")
    }.getOrDefault(if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
    private fun txt(c:Context,value:String,size:Float=15f,color:Int=navy,bold:Boolean=false)=
        TextView(c).apply{
            text=value;textSize=size;setTextColor(color)
            gravity=Gravity.CENTER_VERTICAL or Gravity.RIGHT
            typeface=vazir(c,bold)
            setPadding(dp(c,3),dp(c,5),dp(c,3),dp(c,5))
        }
    private fun button(c:Context,value:String,action:()->Unit)=txt(c,value,14f,blue,true).apply {
        gravity=Gravity.CENTER
        setPadding(dp(c,12),dp(c,11),dp(c,12),dp(c,11))
        background=shape(c,Color.rgb(236,242,255),12,border)
        isClickable=true;setOnClickListener{action()}
    }
    private fun container(c:Context)=LinearLayout(c).apply{
        orientation=LinearLayout.VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_RTL
    }
    private fun inset(c:Context,v:View,top:Int=8,bottom:Int=0){
        (v.parent as? android.view.ViewGroup)?.removeView(v)
    }
    private fun card(c:Context,parent:LinearLayout):LinearLayout{
        val block=container(c).apply {
            setPadding(dp(c,13),dp(c,11),dp(c,13),dp(c,11))
            background=shape(c,Color.WHITE,16,border)
        }
        parent.addView(block,LinearLayout.LayoutParams(-1,-2).apply {
            topMargin=dp(c,10)
        })
        return block
    }
    private fun stopPreview(){runCatching{preview?.stop()};runCatching{preview?.release()};preview=null}
    private var selectedTab=0
    private var editingScreen=false
    private var settingsScreen=false
    fun back(a:Activity):Boolean {
        if(editingScreen || settingsScreen || selectedTab!=0){
            selectedTab=0;show(a);return true
        }
        return false
    }
    /** Full-screen native dashboard. Never displays the main interface in a modal dialog. */
    fun show(a:Activity) {
        stopPreview()
        editingScreen=false;settingsScreen=false
        val root=container(a).apply {
            setBackgroundColor(light)
            setPadding(dp(a,16),dp(a,16),dp(a,16),dp(a,8))
        }
        a.window.statusBarColor=Color.WHITE
        val header=LinearLayout(a).apply{
            layoutDirection=View.LAYOUT_DIRECTION_RTL
            gravity=Gravity.CENTER_VERTICAL
        }
        root.addView(header)
        header.addView(txt(a,"آلارم",26f,navy,true),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button(a,"⚙ تنظیمات"){showSettings(a)})
        root.addView(txt(a,"زنگ‌های دقیق، ساده و قابل اطمینان",13f,muted))
        val tabs=LinearLayout(a).apply{layoutDirection=View.LAYOUT_DIRECTION_RTL}
        root.addView(tabs,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(a,12)})
        listOf("آلارم‌های من","طلوع آفتاب","تقویم شمسی").forEachIndexed{index,label->
            val tab=button(a,label){
                selectedTab=index;show(a)
            }
            if(index==selectedTab) {
                tab.background=shape(a,Color.rgb(69,105,195),12)
                tab.setTextColor(Color.WHITE)
            }
            tabs.addView(tab,LinearLayout.LayoutParams(0,-2,1f).apply{
                marginStart=dp(a,3)
            })
        }
        val scroll=ScrollView(a).apply{
            isFillViewport=true
            isVerticalScrollBarEnabled=false
        }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f).apply{
            topMargin=dp(a,12)
        })
        val content=container(a).apply{setPadding(0,dp(a,4),0,dp(a,18))}
        scroll.addView(content)
        val all=AlarmStore.read(a)
        when(selectedTab){
            0 -> {
                content.addView(button(a,"＋  افزودن آلارم جدید"){
                    edit(a,AlarmItem(title="آلارم جدید"),false)
                })
                if(all.none{!it.sunrise})
                    content.addView(txt(a,"هنوز آلارمی ثبت نکرده‌اید. برای شروع «افزودن آلارم جدید» را بزنید.",
                        15f,muted))
                all.filterNot{it.sunrise}.forEach{item->
                    val block=card(a,content)
                    block.addView(txt(a,item.title,19f,navy,true))
                    block.addView(txt(a,"%02d:%02d".format(item.hour,item.minute)+
                        "   •   "+repeatName(item),15f,muted))
                    block.addView(txt(a,"زنگ بعدی: "+nextText(a,item),13f,muted))
                    block.addView(txt(a,"صدا: "+AlarmSounds.label(item.sound),12f,muted))
                    val controls=LinearLayout(a).apply{layoutDirection=View.LAYOUT_DIRECTION_RTL}
                    block.addView(controls,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(a,8)})
                    controls.addView(button(a,"ویرایش"){edit(a,item,false)},
                        LinearLayout.LayoutParams(0,-2,1f))
                    controls.addView(button(a,if(item.enabled)"خاموش‌کردن" else "فعال‌کردن"){
                        item.enabled=!item.enabled;AlarmStore.update(a,item);show(a)
                    },LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(a,5)})
                    controls.addView(button(a,"حذف"){
                        AlertDialog.Builder(a).setTitle("حذف آلارم")
                            .setMessage("«"+item.title+"» حذف شود؟")
                            .setNegativeButton("انصراف",null)
                            .setPositiveButton("حذف"){_,_->
                                AlarmStore.remove(a,item.id);show(a)
                            }.show()
                    },LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(a,5)})
                }
            }
            1 -> {
                val solar=all.firstOrNull{it.sunrise}?:AlarmItem(
                    id="sunrise-primary",title="آلارم طلوع آفتاب",sunrise=true,enabled=false)
                val section=card(a,content)
                section.addView(txt(a,"آلارم هوشمند طلوع آفتاب",20f,navy,true))
                val place=AlarmStore.location(a)
                section.addView(txt(a,if(place==null)"موقعیت هنوز پیدا نشده است."
                    else "موقعیت: "+AlarmStore.locationLabel(a)+
                        if(AlarmStore.isAutomaticLocation(a))" (خودکار)" else " (انتخاب دستی)",
                    14f,muted))
                if(place!=null){
                    val zone=runCatching{ZoneId.of(place.third)}.getOrDefault(ZoneId.systemDefault())
                    val date=Instant.now().atZone(zone).toLocalDate()
                    val sunrise=SolarMath.sunrise(date,place.first,place.second,zone)
                    section.addView(txt(a,"طلوع امروز: "+
                        if(sunrise==null)"در این تاریخ طلوع رخ نمی‌دهد" else displayTime(sunrise,zone),
                        15f,navy,true))
                }
                section.addView(txt(a,"زنگ "+solar.offset+" دقیقه قبل از طلوع",15f,navy))
                section.addView(txt(a,"نوبت بعد: "+nextText(a,solar),14f,muted))
                section.addView(button(a,"تشخیص خودکار موقعیت فعلی گوشی"){
                    Toast.makeText(a,"در حال دریافت موقعیت دقیق GPS…",Toast.LENGTH_SHORT).show()
                    refreshAutomaticLocation(a,true){show(a)}
                })
                section.addView(button(a,"انتخاب مکان از روی نقشه یا مختصات"){
                    locationEditor(a){show(a)}
                },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(a,8)})
                section.addView(button(a,"تنظیم زمان، روزها و صدای طلوع"){
                    edit(a,solar,true)
                },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(a,8)})
                section.addView(button(a,if(solar.enabled)"خاموش‌کردن آلارم طلوع"
                    else "فعال‌کردن آلارم طلوع"){
                    if(!solar.enabled && AlarmStore.location(a)==null){
                        Toast.makeText(a,"اول موقعیت را به‌صورت خودکار یا دستی تعیین کنید.",
                            Toast.LENGTH_LONG).show()
                    } else {
                        solar.enabled=!solar.enabled;AlarmStore.update(a,solar);show(a)
                    }
                },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(a,8)})
            }
            else -> {
                content.addView(txt(a,"یک تاریخ را انتخاب کنید تا زنگ آن روز را بسازید؛ "+
                    "سپس می‌توانید تکرار ماهانه یا سالانه را تعیین کنید.",15f,muted))
                val calendar=PersianCalendarPicker.view(a,System.currentTimeMillis()){date->
                    val alarm=AlarmItem(title="آلارم جدید",repeat=0,startDate=date,
                        hour=9,minute=0)
                    edit(a,alarm,false)
                }
                val block=card(a,content)
                block.addView(calendar)
            }
        }
        a.setContentView(root)
    }
    private fun showSettings(a:Activity) {
        settingsScreen=true;editingScreen=false
        val root=container(a).apply{
            setBackgroundColor(light)
            setPadding(dp(a,18),dp(a,18),dp(a,18),dp(a,18))
        }
        root.addView(button(a,"بازگشت به آلارم‌ها"){show(a)})
        root.addView(txt(a,"تنظیمات آلارم",24f,navy,true))
        val alarmManager=a.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exact=Build.VERSION.SDK_INT<31 || alarmManager.canScheduleExactAlarms()
        val notification=Build.VERSION.SDK_INT<33 ||
            a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
        val fullscreen=Build.VERSION.SDK_INT<34 ||
            (a.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()
        val location=a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val mic=a.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
        val checks=listOf("آلارم دقیق" to exact,"اعلان‌ها" to notification,
            "نمایش روی صفحه قفل" to fullscreen,"مکان دقیق" to location,
            "ضبط صدای دلخواه" to mic)
        val block=card(a,root)
        checks.forEach{(name,ok)->
            block.addView(txt(a,(if(ok)"✓  " else "⚠  ")+name+" : "+
                if(ok)"فعال" else "نیاز به مجوز",15f,if(ok)navy else Color.rgb(186,77,52)))
        }
        block.addView(button(a,"بازکردن تنظیمات مجوزهای گوشی"){
            a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:"+a.packageName)))
        })
        if(!exact)block.addView(button(a,"فعال‌کردن آلارم دقیق"){
            runCatching{a.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:"+a.packageName)))}
        })
        if(!fullscreen && Build.VERSION.SDK_INT>=34)
            block.addView(button(a,"اجازه نمایش تمام‌صفحه"){
                runCatching{a.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:"+a.packageName)))}
            })
        val test=card(a,root)
        test.addView(txt(a,"تست زنگ روی گوشی",18f,navy,true))
        test.addView(txt(a,"با این آزمایش زنگ واقعی ۱۵ ثانیه بعد اجرا می‌شود؛ "+
            "برای بررسی، صفحه گوشی را قفل کنید.",13f,muted))
        test.addView(button(a,"اجرای تست ۱۵ ثانیه‌ای"){
            val ok=AlarmScheduler.scheduleTest(a,15)
            Toast.makeText(a,if(ok)"تست برای ۱۵ ثانیه بعد ثبت شد"
                else "مجوز آلارم دقیق را فعال کنید",Toast.LENGTH_LONG).show()
        })
        a.setContentView(root)
    }
    private fun displayTime(time:Long,zone:ZoneId=ZoneId.systemDefault())=
        DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(time))
    private fun nextText(a:Activity,item:AlarmItem):String {
        if(!item.enabled)return "غیرفعال"
        val time=AlarmScheduler.next(item,a)?:return "نامشخص؛ موقعیت یا تاریخ را بررسی کنید"
        return persianDate(a,time)+" - "+displayTime(time)
    }
    private fun repeatName(i:AlarmItem):String = when(i.repeat) {
        0->"فقط یک بار، "+persianDateForSummary(i.startDate)
        1->"هر روز"+if(i.endDate==0L)"، نامحدود" else ""
        2->if(i.days==31)"شنبه تا چهارشنبه"+if(i.endDate==0L)"، نامحدود" else ""
           else "روزهای انتخابی: "+arrayOf("شنبه","یکشنبه","دوشنبه","سه‌شنبه",
               "چهارشنبه","پنجشنبه","جمعه").filterIndexed{n,_->i.days and (1 shl n)!=0}
               .joinToString("، ")
        3->"هفتگی"
        4->"ماهانه شمسی"
        else->"سالانه شمسی"
    }
    private fun persianDateForSummary(at:Long):String{
        if(at<=0)return "تاریخ مشخص‌نشده"
        val p=IcuCalendar.getInstance(ULocale("@calendar=persian")).apply{timeInMillis=at}
        return p.get(IcuCalendar.YEAR).toString()+"/"+
            (p.get(IcuCalendar.MONTH)+1).toString()+"/"+
            p.get(IcuCalendar.DAY_OF_MONTH).toString()
    }
    private fun persianDate(c:Context,millis:Long):String {
        val p=IcuCalendar.getInstance(ULocale("@calendar=persian")).apply{timeInMillis=millis}
        return "%04d/%02d/%02d".format(java.util.Locale.US,p.get(IcuCalendar.YEAR),
            p.get(IcuCalendar.MONTH)+1,p.get(IcuCalendar.DAY_OF_MONTH))
    }
    private fun choosePersianDate(a:Activity,initial:Long,onPick:(Long)->Unit) {
        PersianCalendarPicker.show(a,initial,onPick)
    }
    /** The editor is a complete in-app page, not a modal popup. */
    private fun edit(a:Activity,item:AlarmItem,sunrise:Boolean) {
        editingScreen=true;settingsScreen=false
        val copy=AlarmItem.from(item.toJson())
        val root=container(a).apply {
            setBackgroundColor(light)
            setPadding(dp(a,16),dp(a,16),dp(a,16),dp(a,10))
        }
        root.addView(button(a,"بازگشت بدون ذخیره"){show(a)})
        root.addView(txt(a,if(sunrise)"تنظیمات طلوع آفتاب"
            else "تنظیم آلارم",24f,navy,true))
        val scroll=ScrollView(a).apply{
            isFillViewport=false
            isVerticalScrollBarEnabled=false
        }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val form=container(a).apply{setPadding(dp(a,3),dp(a,5),dp(a,3),dp(a,20))}
        scroll.addView(form)
        form.addView(txt(a,"عنوان زنگ",15f,navy,true))
        val title=EditText(a).apply {
            setText(copy.title);hint="مثلاً پرداخت شهریه یا بیدارباش"
            textSize=16f;typeface=vazir(a);setSingleLine(true)
        }
        form.addView(title)
        var selectedMode=when(copy.repeat){
            0->0;1->1;2->if(copy.days==31)2 else 3
            3->4;4->5;else->6
        }
        var customDays=copy.days
        var sunriseDays=when(copy.days){127->0;31->1;else->2}
        val daysLong=arrayOf("شنبه","یکشنبه","دوشنبه","سه‌شنبه","چهارشنبه","پنجشنبه","جمعه")
        fun spinner(options:Array<String>,selection:Int):Spinner =
            Spinner(a).apply {
                adapter=ArrayAdapter(a,android.R.layout.simple_spinner_dropdown_item,options)
                setSelection(selection)
            }
        var offsetMenu:Spinner?=null
        var offsetCustom:EditText?=null
        var endDate=copy.endDate
        var unlimited=copy.endDate==0L && copy.maxOccurrences==0
        var missingDayLast=copy.missingDayLast
        val weekdays=container(a)
        val advanced=container(a)
        fun daySelector(initial:Int) {
            weekdays.removeAllViews()
            weekdays.addView(txt(a,"روزهای زنگ‌خوردن",14f,navy,true))
            daysLong.forEachIndexed{idx,name->
                val cb=CheckBox(a).apply{
                    text=name;typeface=vazir(a)
                    isChecked=(customDays and (1 shl idx))!=0
                    setOnCheckedChangeListener{_,checked->
                        customDays=if(checked)customDays or (1 shl idx)
                            else customDays and (1 shl idx).inv()
                    }
                }
                weekdays.addView(cb)
            }
        }
        if(!sunrise) {
            val clock=button(a,"زمان زنگ: %02d:%02d".format(copy.hour,copy.minute)) {}
            clock.setOnClickListener {
                TimePickerDialog(a,{_,h,m->
                    copy.hour=h;copy.minute=m
                    clock.text="زمان زنگ: %02d:%02d".format(h,m)
                },copy.hour,copy.minute,true).show()
            }
            form.addView(txt(a,"ساعت",15f,navy,true))
            form.addView(clock)
            val modes=arrayOf("فقط یک بار، در تاریخ مشخص","هر روز",
                "شنبه تا چهارشنبه","روزهای دلخواه هفته","هر هفته در همین روز",
                "هر ماه شمسی","هر سال شمسی")
            val repeatMenu=spinner(modes,selectedMode)
            form.addView(txt(a,"زنگ چند وقت یک‌بار تکرار شود؟",15f,navy,true))
            form.addView(repeatMenu)
            val dateButton=button(a,"تاریخ: "+persianDate(a,
                if(copy.startDate>0)copy.startDate else System.currentTimeMillis())){}
            dateButton.setOnClickListener{
                PersianCalendarPicker.show(a,if(copy.startDate>0)copy.startDate
                    else System.currentTimeMillis()){chosen->
                    copy.startDate=chosen
                    dateButton.text="تاریخ: "+persianDate(a,chosen)
                }
            }
            form.addView(dateButton)
            form.addView(advanced)
            fun updateDetails(){
                selectedMode=repeatMenu.selectedItemPosition
                advanced.removeAllViews()
                weekdays.removeAllViews()
                if(selectedMode==3) {
                    daySelector(customDays)
                    advanced.addView(weekdays)
                }
                if(selectedMode==2)
                    advanced.addView(txt(a,
                        "به‌طور خودکار هر هفته شنبه، یکشنبه، دوشنبه، سه‌شنبه و چهارشنبه زنگ می‌خورد.",
                        13f,muted))
                if(selectedMode in 5..6)
                    advanced.addView(txt(a,"این تکرار براساس تاریخ شمسی انتخاب‌شده محاسبه می‌شود؛ "+
                        "روزهای هفته در آن نقشی ندارند.",13f,muted))
                if(selectedMode==0) {
                    advanced.addView(txt(a,"این زنگ فقط یک‌بار در تاریخ انتخاب‌شده اجرا می‌شود.",
                        13f,muted))
                    return
                }
                val unlimitedBox=CheckBox(a).apply {
                    text="تکرار بدون تاریخ پایان (نامحدود)"
                    typeface=vazir(a);isChecked=unlimited
                }
                advanced.addView(unlimitedBox)
                advanced.addView(txt(a,
                    "نامحدود یعنی زنگ طبق این برنامه ادامه دارد تا خودتان آن را خاموش یا حذف کنید.",
                    12f,muted))
                val limit=container(a)
                val until=button(a,if(endDate>0)"تاریخ پایان: "+persianDate(a,endDate)
                    else "انتخاب تاریخ پایان"){}
                until.setOnClickListener {
                    PersianCalendarPicker.show(a,if(endDate>0)endDate
                        else System.currentTimeMillis()){chosen->
                        endDate=chosen
                        until.text="تاریخ پایان: "+persianDate(a,chosen)
                    }
                }
                limit.addView(until)
                advanced.addView(limit)
                limit.visibility=if(unlimited)View.GONE else View.VISIBLE
                unlimitedBox.setOnCheckedChangeListener{_,yes->
                    unlimited=yes
                    limit.visibility=if(yes)View.GONE else View.VISIBLE
                }
                if(selectedMode==5 || selectedMode==6){
                    val shortMonth=CheckBox(a).apply {
                        text="اگر این روز در ماه مقصد نبود، آخرین روز آن ماه انتخاب شود"
                        typeface=vazir(a);isChecked=missingDayLast
                        setOnCheckedChangeListener{_,yes->missingDayLast=yes}
                    }
                    advanced.addView(shortMonth)
                }
            }
            repeatMenu.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,
                    view:View?,position:Int,id:Long){updateDetails()}
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            }
            updateDetails()
        }else {
            form.addView(txt(a,"زنگ چند دقیقه قبل از طلوع اجرا شود؟",15f,navy,true))
            val intervals=listOf(0,5,10,15,20,30,45,60)
            val index=intervals.indexOf(copy.offset)
            val intervalsMenu=spinner((intervals.map{
                if(it==0)"همزمان با طلوع" else "$it دقیقه قبل"
            }+listOf("مقدار دلخواه")).toTypedArray(),if(index>=0)index else 8)
            offsetMenu=intervalsMenu
            form.addView(intervalsMenu)
            val custom=EditText(a).apply{
                hint="فاصله به دقیقه";setText(copy.offset.toString())
                inputType=android.text.InputType.TYPE_CLASS_NUMBER
                typeface=vazir(a)
            }
            offsetCustom=custom
            form.addView(custom)
            intervalsMenu.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,
                    view:View?,position:Int,id:Long){
                    custom.visibility=if(position==8)View.VISIBLE else View.GONE
                }
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            }
            form.addView(txt(a,"کدام روزها؟",15f,navy,true))
            val sunMenu=spinner(arrayOf("هر روز","شنبه تا چهارشنبه","روزهای دلخواه"),sunriseDays)
            form.addView(sunMenu)
            form.addView(weekdays)
            fun updateSunDays(){
                sunriseDays=sunMenu.selectedItemPosition
                if(sunriseDays==2) {
                    daySelector(customDays)
                    weekdays.visibility=View.VISIBLE
                }else weekdays.visibility=View.GONE
            }
            sunMenu.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,
                    view:View?,position:Int,id:Long){updateSunDays()}
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            }
            updateSunDays()
        }
        form.addView(txt(a,"صدای زنگ",15f,navy,true))
        val soundName=txt(a,AlarmSounds.label(copy.sound),14f,muted)
        form.addView(soundName)
        form.addView(button(a,"انتخاب و شنیدن صدا"){
            soundPicker(a){chosen->copy.sound=chosen;soundName.text=AlarmSounds.label(chosen)}
        })
        val vibrate=CheckBox(a).apply{
            text="لرزش همراه با زنگ";typeface=vazir(a);isChecked=copy.vibrate
        }
        val gradual=CheckBox(a).apply{
            text="بلندشدن تدریجی صدا";typeface=vazir(a);isChecked=copy.gradual
        }
        form.addView(vibrate);form.addView(gradual)
        form.addView(txt(a,"مدت تعویق زنگ",14f,navy,true))
        val snoozes=listOf(5,10,15)
        val snoozeMenu=spinner(arrayOf("۵ دقیقه","۱۰ دقیقه","۱۵ دقیقه"),
            snoozes.indexOf(copy.snooze).takeIf{it>=0}?:1)
        form.addView(snoozeMenu)
        val save=button(a,"ذخیره آلارم"){}
        save.background=shape(a,blue,14)
        save.setTextColor(Color.WHITE)
        save.setOnClickListener {
            copy.title=title.text.toString().trim().ifBlank{
                if(sunrise)"آلارم طلوع آفتاب" else "آلارم"
            }
            if(sunrise) {
                val intervals=listOf(0,5,10,15,20,30,45,60)
                copy.offset=if(offsetMenu?.selectedItemPosition==8)
                    offsetCustom?.text?.toString()?.toIntOrNull()?.takeIf{it in 0..1440}?:-1
                    else intervals.getOrElse(offsetMenu?.selectedItemPosition?:3){15}
                if(copy.offset<0) {
                    Toast.makeText(a,"فاصله طلوع باید بین صفر تا ۱۴۴۰ دقیقه باشد",
                        Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                copy.days=when(sunriseDays){0->127;1->31;else->customDays}
                if(copy.days==0){
                    Toast.makeText(a,"حداقل یک روز را انتخاب کنید",Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if(AlarmStore.location(a)==null) {
                    copy.enabled=false
                    Toast.makeText(a,"موقعیت مشخص نیست؛ آلارم تا تعیین موقعیت خاموش می‌ماند.",
                        Toast.LENGTH_LONG).show()
                }
            }else {
                copy.repeat=when(selectedMode) {
                    0->0;1->1;2,3->2;4->3;5->4;else->5
                }
                copy.days=when(selectedMode){
                    2->31;3->customDays;else->127
                }
                if(selectedMode==3 && copy.days==0){
                    Toast.makeText(a,"حداقل یک روز را انتخاب کنید",Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if(copy.startDate<=0){
                    copy.startDate=LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
                }
                if(copy.repeat==0) {
                    copy.endDate=0;copy.maxOccurrences=1
                }else {
                    if(!unlimited && endDate<=0){
                        Toast.makeText(a,"تاریخ پایان را انتخاب کنید",Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    copy.endDate=if(unlimited)0 else endDate
                    copy.maxOccurrences=0
                    if(copy.endDate>0 && copy.endDate<copy.startDate){
                        Toast.makeText(a,"تاریخ پایان نمی‌تواند قبل از تاریخ شروع باشد",
                            Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                }
                copy.missingDayLast=missingDayLast
                copy.firedCount=0
                if(copy.repeat==0 && AlarmScheduler.next(copy,a)==null) {
                    Toast.makeText(a,"تاریخ و ساعت انتخاب‌شده گذشته است",
                        Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }
            copy.vibrate=vibrate.isChecked
            copy.gradual=gradual.isChecked
            copy.snooze=snoozes[snoozeMenu.selectedItemPosition.coerceIn(0,2)]
            AlarmStore.update(a,copy)
            show(a)
        }
        form.addView(save,LinearLayout.LayoutParams(-1,-2).apply{
            topMargin=dp(a,20);bottomMargin=dp(a,12)
        })
        a.setContentView(root)
    }
    private fun soundPicker(a:Activity,onChoose:(String)->Unit){
        stopPreview()
        val layout=container(a).apply{setPadding(dp(a,18),dp(a,5),dp(a,18),dp(a,12))}
        val scroll=ScrollView(a).apply{addView(layout)}
        val picker=AlertDialog.Builder(a).setTitle("انتخاب صدای آلارم")
            .setView(scroll).setNegativeButton("بستن",null).create()
        AlarmSounds.labels.forEachIndexed{idx,label->
            val row=LinearLayout(a).apply{gravity=Gravity.CENTER_VERTICAL}
            layout.addView(row)
            row.addView(button(a,"▶"){playPreview(a,"preset:$idx")},
                LinearLayout.LayoutParams(dp(a,57),-2))
            row.addView(txt(a,label,14f,navy),LinearLayout.LayoutParams(0,-2,1f).apply{
                marginStart=dp(a,8)
            })
            row.addView(button(a,"انتخاب"){onChoose("preset:$idx");picker.dismiss()})
        }
        layout.addView(button(a,"انتخاب فایل از گوشی"){
            chooser={path->onChoose(path)}
            a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE);type="audio/*"
            },PICK_AUDIO)
            picker.dismiss()
        },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(a,14)})
        layout.addView(button(a,"ضبط صدای آلارم"){
            picker.dismiss()
            requestRecorder(a,onChoose)
        },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(a,8)})
        picker.setOnDismissListener{stopPreview()}
        picker.show()
    }
    private fun playPreview(a:Activity,sound:String){
        stopPreview()
        val file=AlarmSounds.file(a,sound)?:return
        runCatching{
            preview=MediaPlayer().apply{
                setAudioAttributes(AudioAttributes.Builder().setUsage(
                    AudioAttributes.USAGE_ALARM).build())
                setDataSource(file.absolutePath);prepare();start()
                setOnCompletionListener{stopPreview()}
            }
        }.onFailure{stopPreview()}
    }
    fun receivePickedAudio(a:Activity,data:Intent?) {
        val uri=data?.data?:return
        val dir=File(a.filesDir,"alarm_sounds").apply{mkdirs()}
        var originalName="صدای انتخابی"
        runCatching {
            a.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,null,null)?.use {cursor->
                if(cursor.moveToFirst()) originalName=cursor.getString(0) ?: originalName
            }
        }
        val clean=originalName.replace(Regex("[/\\\\\\x00-\\x1F]"),"_").take(90)
            .ifBlank{"صدای انتخابی"}
        val base=clean.substringBeforeLast(".",clean).ifBlank{"صدای انتخابی"}
        val ext=clean.substringAfterLast(".", "audio").take(8)
        var candidateName=clean
        var index=2
        while(File(dir,candidateName).exists()){
            candidateName=base+" ("+index+")"+"."+ext
            index++
        }
        val file=File(dir,candidateName)
        val copied=runCatching{
            a.contentResolver.openInputStream(uri)?.use{source->
                file.outputStream().use{dest->
                    val bytes=ByteArray(8192);var total=0L
                    while(true){
                        val count=source.read(bytes);if(count<0)break
                        total+=count;if(total>25L*1024*1024)error("Too large")
                        dest.write(bytes,0,count)
                    }
                    total>0
                }
            }==true
        }.getOrDefault(false)
        if(copied)chooser?.invoke(file.absolutePath)
        else{file.delete();Toast.makeText(a,"فایل قابل استفاده نبود یا بیش از ۲۵ مگابایت است",
            Toast.LENGTH_LONG).show()}
        chooser=null
    }
    private fun mapLocationPicker(a:Activity,lat:Double,lon:Double,onChoose:(Double,Double)->Unit) {
        val web=WebView(a).apply {
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            webViewClient=WebViewClient()
        }
        val selected=doubleArrayOf(lat,lon)
        val shell=container(a)
        shell.addView(txt(a,"روی نقشه بزنید تا مکان انتخاب شود، سپس تأیید کنید.",13f,muted))
        shell.addView(web,LinearLayout.LayoutParams(-1,dp(a,440)))
        val status=txt(a,"عرض: $lat | طول: $lon",13f,muted)
        shell.addView(status)
        class Selection {
            @JavascriptInterface fun pick(latitude:Double,longitude:Double) {
                if(latitude !in -90.0..90.0 || longitude !in -180.0..180.0)return
                a.runOnUiThread {
                    selected[0]=latitude;selected[1]=longitude
                    status.text="عرض: $latitude | طول: $longitude"
                }
            }
        }
        web.addJavascriptInterface(Selection(),"AndroidPicker")
        val page="""<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
            <style>html,body,#map{height:100%;margin:0}</style></head><body><div id="map"></div>
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <script>
            var map=L.map('map').setView([LAT,LON],12);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
              {maxZoom:19,attribution:'&copy; OpenStreetMap contributors'}).addTo(map);
            var marker=L.marker([LAT,LON]).addTo(map);
            map.on('click',function(e){marker.setLatLng(e.latlng);
              AndroidPicker.pick(e.latlng.lat,e.latlng.lng);});
            </script></body></html>"""
            .replace("LAT",lat.toString()).replace("LON",lon.toString())
        web.loadDataWithBaseURL("https://localhost/",page,"text/html","UTF-8",null)
        val dialog=AlertDialog.Builder(a).setTitle("انتخاب مکان روی نقشه")
            .setView(shell).setNegativeButton("انصراف",null)
            .setPositiveButton("✓ تأیید مکان"){_,_->onChoose(selected[0],selected[1])}
            .create()
        dialog.setOnDismissListener{web.destroy()}
        dialog.show()
    }
    private fun locationEditor(a:Activity,done:()->Unit) {
        val layout=container(a).apply{setPadding(dp(a,21),dp(a,10),dp(a,21),dp(a,10))}
        val current=AlarmStore.location(a)
        fun input(hint:String,value:String)=EditText(a).apply{
            this.hint=hint;setText(value);setSingleLine(true);textSize=15f
        }
        val lat=input("عرض جغرافیایی (مثلاً 35.7)",current?.first?.toString()?:"")
        val lon=input("طول جغرافیایی (مثلاً 51.4)",current?.second?.toString()?:"")
        val zone=input("منطقه زمانی",current?.third?:TimeZone.getDefault().id)
        val place=input("نام موقعیت",if(current==null)"" else AlarmStore.locationLabel(a))
        for(v in listOf(lat,lon,zone,place))layout.addView(v)
        layout.addView(button(a,"انتخاب مکان از روی نقشه"){
            val startLat=lat.text.toString().toDoubleOrNull()?:current?.first?:35.7
            val startLon=lon.text.toString().toDoubleOrNull()?:current?.second?:51.4
            mapLocationPicker(a,startLat,startLon){latitude,longitude->
                lat.setText(latitude.toString())
                lon.setText(longitude.toString())
                place.setText("مکان انتخاب‌شده روی نقشه")
            }
        })
        layout.addView(button(a,"دریافت موقعیت فعلی گوشی"){
            locateAfterPermission={
                readLocation(a){latitude,longitude->
                    lat.setText(latitude.toString());lon.setText(longitude.toString())
                    place.setText("موقعیت فعلی")
                }
            }
            if(a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==
                PackageManager.PERMISSION_GRANTED)locateAfterPermission?.invoke()
            else a.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION),LOCATION_PERMISSION)
        })
        AlertDialog.Builder(a).setTitle("موقعیت آلارم طلوع")
            .setView(layout).setNegativeButton("انصراف",null)
            .setPositiveButton("ذخیره"){_,_->
                val latitude=lat.text.toString().toDoubleOrNull()
                val longitude=lon.text.toString().toDoubleOrNull()
                if(latitude==null || longitude==null || latitude !in -90.0..90.0 ||
                    longitude !in -180.0..180.0){
                    Toast.makeText(a,"مختصات نامعتبر است",Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val timezone=zone.text.toString().ifBlank{TimeZone.getDefault().id}
                runCatching{
                    AlarmStore.setLocation(a,latitude,longitude,timezone,
                        place.text.toString().ifBlank{"مختصات ذخیره‌شده"})
                }.onSuccess{done()}.onFailure{
                    Toast.makeText(a,"منطقه زمانی معتبر نیست",Toast.LENGTH_LONG).show()
                }
            }.show()
    }
    /**
     * GPS and network are both attempted. Cached coordinates are accepted only if recent,
     * never as a silent fallback to an arbitrarily old location.
     */
    private fun readLocation(a:Activity,callback:(Double,Double)->Unit) {
        if(a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=
            PackageManager.PERMISSION_GRANTED)return
        val mgr=a.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers=listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER)
            .filter {runCatching{mgr.isProviderEnabled(it)}.getOrDefault(false)}
        if(providers.isEmpty()){
            Toast.makeText(a,"مکان‌یابی گوشی خاموش است؛ GPS را روشن کنید",Toast.LENGTH_LONG).show()
            return
        }
        val cached=providers.mapNotNull { p ->
            runCatching {mgr.getLastKnownLocation(p)}.getOrNull()
        }.filter {it.time>System.currentTimeMillis()-2*60*60*1000L}
            .minByOrNull {it.accuracy}
        if(cached!=null && cached.accuracy<=2000f) {
            callback(cached.latitude,cached.longitude)
            return
        }
        fun query(index:Int) {
            if(index>=providers.size) {
                Toast.makeText(a,"موقعیت پیدا نشد؛ GPS را روشن کنید یا از نقشه انتخاب کنید",
                    Toast.LENGTH_LONG).show()
                return
            }
            val provider=providers[index]
            if(Build.VERSION.SDK_INT>=30) {
                runCatching {
                    mgr.getCurrentLocation(provider,null,a.mainExecutor){loc->
                        if(loc!=null)callback(loc.latitude,loc.longitude)
                        else query(index+1)
                    }
                }.onFailure { query(index+1) }
            } else {
                val loc=runCatching{mgr.getLastKnownLocation(provider)}.getOrNull()
                if(loc!=null)callback(loc.latitude,loc.longitude) else query(index+1)
            }
        }
        query(0)
    }
    /** Automatically locate only new/previously auto-located alarms; never override a map pin. */
    fun refreshAutomaticLocation(a:Activity,updated:()->Unit={}) {
        if(AlarmStore.location(a)!=null && !AlarmStore.isAutomaticLocation(a))return
        if(a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=
            PackageManager.PERMISSION_GRANTED)return
        readLocation(a){lat,lon->
            if(AlarmStore.location(a)==null || AlarmStore.isAutomaticLocation(a)){
                runCatching {
                    AlarmStore.setLocation(a,lat,lon,ZoneId.systemDefault().id,
                        "موقعیت فعلی گوشی","auto")
                }.onSuccess { updated() }
            }
        }
    }
    fun onLocationPermissionResult(granted:Boolean){
        if(granted)locateAfterPermission?.invoke()
        locateAfterPermission=null
    }
    private fun requestRecorder(a:Activity,onSave:(String)->Unit){
        if(a.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=
            PackageManager.PERMISSION_GRANTED){
            recordAfterPermission={recordDialog(a,onSave)}
            a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),MIC_PERMISSION)
        }else recordDialog(a,onSave)
    }
    fun onMicPermissionResult(granted:Boolean){
        if(granted)recordAfterPermission?.invoke()
        recordAfterPermission=null
    }
    private fun stopRecorder(keep:Boolean):File? {
        val current=recorder;recorder=null
        waveformTick?.let{waveformHandler?.removeCallbacks(it)}
        waveformTick=null
        val file=recordingFile;recordingFile=null
        val success=runCatching{current?.stop()}.isSuccess
        runCatching{current?.release()}
        if(!keep || !success || file==null || file.length()<256){
            file?.delete();return null
        }
        return file
    }
    private fun recordDialog(a:Activity,onSave:(String)->Unit){
        stopPreview()
        val content=container(a).apply{setPadding(dp(a,20),dp(a,8),dp(a,20),dp(a,8))}
        val status=txt(a,"برای شروع، دکمه ضبط را لمس کن",13f,muted)
        content.addView(status)
        val levels=FloatArray(30){0.05f}
        val wave=object:View(a){
            val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas:Canvas){
                super.onDraw(canvas)
                val step=width/levels.size.toFloat()
                for(i in levels.indices){
                    paint.color=blue
                    val heightBar=(height*levels[i]).coerceAtLeast(5f)
                    canvas.drawRoundRect(i*step+2,(height-heightBar)/2,
                        (i+1)*step-2,(height+heightBar)/2,4f,4f,paint)
                }
            }
        }
        content.addView(wave,LinearLayout.LayoutParams(-1,dp(a,82)))
        val recordButton=button(a,"● شروع ضبط"){}
        content.addView(recordButton)
        val playButton=button(a,"▶ پیش‌نمایش"){}
        content.addView(playButton)
        var completed:File?=null
        val dialog=AlertDialog.Builder(a).setTitle("ضبط صدای آلارم")
            .setView(content).setNegativeButton("انصراف",null)
            .setPositiveButton("انتخاب این صدا",null).create()
        recordButton.setOnClickListener {
            if(recorder!=null){
                completed=stopRecorder(true)
                status.text=if(completed==null)"فایل ضبط نشد" else "صدا آماده است"
                recordButton.text="● ضبط مجدد"
            }else{
                completed?.delete();completed=null
                val dir=File(a.filesDir,"alarm_sounds").apply{mkdirs()}
                val file=File(dir,UUID.randomUUID().toString()+".m4a")
                val candidate=if(Build.VERSION.SDK_INT>=31)MediaRecorder(a) else {
                    @Suppress("DEPRECATION") MediaRecorder()
                }
                val started=runCatching{
                    candidate.setAudioSource(MediaRecorder.AudioSource.MIC)
                    candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    candidate.setOutputFile(file.absolutePath)
                    candidate.prepare();candidate.start()
                }.isSuccess
                if(!started){runCatching{candidate.release()};file.delete()
                    status.text="ضبط شروع نشد";return@setOnClickListener}
                recorder=candidate;recordingFile=file
                recordButton.text="■ توقف ضبط"
                status.text="در حال ضبط؛ موج با شدت صدای واقعی تغییر می‌کند"
                val handler=Handler(Looper.getMainLooper());waveformHandler=handler
                val ticker=object:Runnable{
                    override fun run(){
                        if(recorder!==candidate)return
                        levels.copyInto(levels,0,1,levels.size)
                        levels[levels.lastIndex]=(candidate.maxAmplitude/32767f)
                            .coerceIn(0.04f,1f)
                        wave.invalidate()
                        handler.postDelayed(this,90L)
                    }
                }
                waveformTick=ticker;handler.post(ticker)
            }
        }
        playButton.setOnClickListener{
            if(recorder!=null)return@setOnClickListener
            completed?.takeIf{it.isFile}?.let{file->
                stopPreview()
                runCatching{
                    preview=MediaPlayer().apply{
                        setDataSource(file.absolutePath);prepare();start()
                    }
                }
            }
        }
        dialog.setOnDismissListener{stopRecorder(false);stopPreview()}
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            if(recorder!=null) completed=stopRecorder(true)
            val file=completed
            if(file!=null && file.isFile){onSave(file.absolutePath);dialog.dismiss()}
            else Toast.makeText(a,"ابتدا یک صدای معتبر ضبط کن",Toast.LENGTH_LONG).show()
        }
    }
}
