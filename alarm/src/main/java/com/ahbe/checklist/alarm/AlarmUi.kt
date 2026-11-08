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
    private fun repeatName(i:AlarmItem)=when(i.repeat){
        0->"یک‌بار";1->"هر روز";2->"روزهای انتخابی";3->"هفتگی";4->"ماهانه شمسی";else->"سالانه شمسی"
    }
    private fun persianDate(c:Context,millis:Long):String {
        val p=IcuCalendar.getInstance(ULocale("@calendar=persian")).apply{timeInMillis=millis}
        return "%04d/%02d/%02d".format(java.util.Locale.US,p.get(IcuCalendar.YEAR),
            p.get(IcuCalendar.MONTH)+1,p.get(IcuCalendar.DAY_OF_MONTH))
    }
    private fun choosePersianDate(a:Activity,initial:Long,onPick:(Long)->Unit) {
        val p=IcuCalendar.getInstance(ULocale("@calendar=persian")).apply{timeInMillis=if(initial>0)initial else System.currentTimeMillis()}
        val row=LinearLayout(a).apply{
            orientation=LinearLayout.HORIZONTAL;layoutDirection=View.LAYOUT_DIRECTION_RTL
            setPadding(dp(a,12),dp(a,10),dp(a,12),dp(a,10))
        }
        val year=NumberPicker(a).apply{minValue=1300;maxValue=1600;value=p.get(IcuCalendar.YEAR)}
        val month=NumberPicker(a).apply{minValue=1;maxValue=12;value=p.get(IcuCalendar.MONTH)+1}
        val day=NumberPicker(a).apply{minValue=1;maxValue=31;value=p.get(IcuCalendar.DAY_OF_MONTH)}
        fun refresh(){
            val probe=IcuCalendar.getInstance(ULocale("@calendar=persian")).apply{
                set(IcuCalendar.YEAR,year.value);set(IcuCalendar.MONTH,month.value-1)
                set(IcuCalendar.DAY_OF_MONTH,1)
            }
            day.maxValue=probe.getActualMaximum(IcuCalendar.DAY_OF_MONTH)
        }
        year.setOnValueChangedListener{_,_,_->refresh()}
        month.setOnValueChangedListener{_,_,_->refresh()}
        refresh()
        for(picker in listOf(day,month,year))row.addView(picker,LinearLayout.LayoutParams(0,-2,1f))
        AlertDialog.Builder(a).setTitle("انتخاب تاریخ شمسی").setView(row)
            .setNegativeButton("انصراف",null).setPositiveButton("تأیید"){_,_->
                val chosen=IcuCalendar.getInstance(ULocale("@calendar=persian")).apply{
                    clear();set(IcuCalendar.YEAR,year.value);set(IcuCalendar.MONTH,month.value-1)
                    set(IcuCalendar.DAY_OF_MONTH,day.value)
                    set(IcuCalendar.HOUR_OF_DAY,12)
                }
                val local=Instant.ofEpochMilli(chosen.timeInMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                onPick(local.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            }.show()
    }
    private fun edit(a:Activity,item:AlarmItem,sunrise:Boolean,parent:Dialog) {
        val copy=AlarmItem.from(item.toJson())
        val layout=container(a).apply{setPadding(dp(a,18),dp(a,12),dp(a,18),dp(a,8))}
        val outer=ScrollView(a).apply{addView(layout)}
        val title=EditText(a).apply {
            setText(copy.title);hint="عنوان آلارم"
            setSingleLine(true);textSize=17f;typeface=vazir(a)
        }
        layout.addView(txt(a,"نام آلارم",14f,navy,true))
        layout.addView(title)
        if(!sunrise) {
            val time=button(a,"ساعت: %02d:%02d".format(copy.hour,copy.minute)){
                TimePickerDialog(a,{_,h,m->
                    copy.hour=h;copy.minute=m
                    // This button is regenerated by rebuilding its own label.
                    clockButton?.text="ساعت: %02d:%02d".format(h,m)
                },copy.hour,copy.minute,true).show()
            }
            clockButton=time
            layout.addView(time,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(a,8)})
            val repeat=Spinner(a)
            val options=arrayOf("فقط یک‌بار","هر روز","روزهای انتخابی","هفتگی","ماهانه شمسی","سالانه شمسی")
            repeat.adapter=ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,options).apply{setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)}
            repeat.setSelection(copy.repeat)
            layout.addView(txt(a,"نوع تکرار",14f,navy,true));layout.addView(repeat)
            lateinit var dateButton:TextView
            dateButton=button(a,"تاریخ شروع: "+persianDate(a,if(copy.startDate>0)copy.startDate else System.currentTimeMillis())){
                choosePersianDate(a,copy.startDate){selected->
                    copy.startDate=selected
                    dateButtonRef?.text="تاریخ شروع: "+persianDate(a,selected)
                }
            }
            dateButtonRef=dateButton
            layout.addView(dateButton)
            val endButton=button(a,if(copy.endDate>0)"تاریخ پایان: "+persianDate(a,copy.endDate) else "تاریخ پایان: نامحدود"){
                choosePersianDate(a,copy.endDate){selected->
                    copy.endDate=selected
                    endButtonRef?.text="تاریخ پایان: "+persianDate(a,selected)
                }
            }
            endButtonRef=endButton
            layout.addView(endButton)
            layout.addView(button(a,"حذف تاریخ پایان"){copy.endDate=0;endButton.text="تاریخ پایان: نامحدود"})
            val count=EditText(a).apply{
                hint="تعداد دفعات (خالی = نامحدود)";typeface=vazir(a)
                inputType=android.text.InputType.TYPE_CLASS_NUMBER
                setText(if(copy.maxOccurrences>0)copy.maxOccurrences.toString() else "")
            }
            layout.addView(txt(a,"تعداد تکرار",13f,muted))
            layout.addView(count)
            countInput=count
            val lastDay=CheckBox(a).apply{
                text="در ماه کوتاه‌تر، آخرین روز ماه انتخاب شود";typeface=vazir(a)
                isChecked=copy.missingDayLast
            }
            layout.addView(lastDay)
            lastDayInput=lastDay
            repeat.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
                override fun onItemSelected(p:android.widget.AdapterView<*>?,v:View?,pos:Int,id:Long){
                    copy.repeat=pos
                }
                override fun onNothingSelected(p:android.widget.AdapterView<*>?){}
            }
        }else {
            layout.addView(txt(a,"فاصله نسبت به طلوع (دقیقه قبل)",14f,navy,true))
            val offsetOptions=listOf(0,5,10,15,20,30,45,60)
            val opts=offsetOptions.map {if(it==0)"هم‌زمان با طلوع" else "$it دقیقه قبل"}+"زمان دلخواه"
            val offset=Spinner(a).apply {
                adapter=ArrayAdapter(a,android.R.layout.simple_spinner_dropdown_item,opts)
                setSelection(offsetOptions.indexOf(copy.offset).takeIf{it>=0}?:8)
            }
            layout.addView(offset)
            val custom=EditText(a).apply{
                hint="دقیقه دلخواه";typeface=vazir(a);inputType=android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(copy.offset.toString())
            }
            layout.addView(custom)
            offset.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
                override fun onItemSelected(p:android.widget.AdapterView<*>?,v:View?,pos:Int,id:Long){
                    custom.visibility=if(pos==8)View.VISIBLE else View.GONE
                }
                override fun onNothingSelected(p:android.widget.AdapterView<*>?){}
            }
            // Save action below validates offset.
            custom.tag=offset
        }
        layout.addView(txt(a,"روزهای هفته",14f,navy,true).apply{setPadding(0,dp(a,15),0,dp(a,5))})
        val checks=mutableListOf<CheckBox>()
        val dayRow=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
        layout.addView(dayRow)
        days.forEachIndexed{idx,d->
            val cb=CheckBox(a).apply {
                text=d;typeface=vazir(a);buttonTintList=ColorStateList.valueOf(blue)
                isChecked=copy.days and (1 shl idx)!=0
                setPadding(0,0,0,0)
            }
            checks.add(cb)
            dayRow.addView(cb,LinearLayout.LayoutParams(0,dp(a,45),1f))
        }
        layout.addView(txt(a,"صدای زنگ",14f,navy,true).apply{setPadding(0,dp(a,12),0,0)})
        val soundName=txt(a,AlarmSounds.label(copy.sound),14f,muted)
        layout.addView(soundName)
        layout.addView(button(a,"انتخاب و پیش‌شنیدن صدا"){
            soundPicker(a){sound->copy.sound=sound;soundName.text=AlarmSounds.label(sound)}
        })
        val vibrate=CheckBox(a).apply{text="لرزش";typeface=vazir(a);isChecked=copy.vibrate}
        val gradual=CheckBox(a).apply{text="افزایش تدریجی صدا";typeface=vazir(a);isChecked=copy.gradual}
        layout.addView(vibrate);layout.addView(gradual)
        val snoozeOptions=listOf("۵ دقیقه","۱۰ دقیقه","۱۵ دقیقه")
        val snooze=Spinner(a).apply{
            adapter=ArrayAdapter(a,android.R.layout.simple_spinner_dropdown_item,snoozeOptions)
            setSelection(when(copy.snooze){5->0;15->2;else->1})
        }
        layout.addView(txt(a,"تعویق پیش‌فرض",13f,muted));layout.addView(snooze)
        val editor=AlertDialog.Builder(a).setTitle(if(sunrise)"آلارم طلوع" else "تنظیم آلارم")
            .setView(outer).setNegativeButton("انصراف",null)
            .setPositiveButton("ذخیره",null).create()
        editor.setOnDismissListener{stopPreview()}
        editor.show()
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            copy.title=title.text.toString().trim().ifBlank{if(sunrise)"آلارم طلوع آفتاب" else "آلارم"}
            copy.days=0
            checks.forEachIndexed{idx,box->if(box.isChecked)copy.days=copy.days or (1 shl idx)}
            if(sunrise) {
                val offsetSpinner=layout.getChildAt(
                    (0 until layout.childCount).firstOrNull {i->
                        layout.getChildAt(i) is EditText && layout.getChildAt(i).tag is Spinner
                    }?:-1
                ) as? EditText
                val spin=offsetSpinner?.tag as? Spinner
                val preset=listOf(0,5,10,15,20,30,45,60)
                copy.offset=if(spin?.selectedItemPosition==8)
                    offsetSpinner.text.toString().toIntOrNull()?.coerceIn(-1440,1440)?:15
                else preset.getOrElse(spin?.selectedItemPosition?:3){15}
            }
            if((sunrise || copy.repeat==2) && copy.days==0){
                Toast.makeText(a,"حداقل یک روز هفته را انتخاب کن",Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if(!sunrise){
                if(copy.startDate==0L)copy.startDate=LocalDate.now()
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if(copy.endDate>0 && copy.endDate<copy.startDate){
                    Toast.makeText(a,"تاریخ پایان نباید قبل از شروع باشد",Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                copy.maxOccurrences=countInput?.text?.toString()?.toIntOrNull()?.coerceAtLeast(0)?:0
                copy.missingDayLast=lastDayInput?.isChecked?:true
                if(copy.repeat==0)copy.maxOccurrences=1
                if(copy.next>0)copy.firedCount=0
            }
            copy.vibrate=vibrate.isChecked;copy.gradual=gradual.isChecked
            copy.snooze=listOf(5,10,15)[snooze.selectedItemPosition.coerceIn(0,2)]
            if(sunrise && AlarmStore.location(a)==null) {
                copy.enabled=false
                Toast.makeText(a,"برای فعال‌کردن آلارم طلوع، موقعیت را ثبت کنید",
                    Toast.LENGTH_LONG).show()
            }
            AlarmStore.update(a,copy)
            editor.dismiss();parent.dismiss();show(a)
        }
    }
    private var clockButton:TextView?=null
    private var dateButtonRef:TextView?=null
    private var endButtonRef:TextView?=null
    private var countInput:EditText?=null
    private var lastDayInput:CheckBox?=null
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
