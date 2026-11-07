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
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.time.*
import android.icu.util.PersianCalendar
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
    private fun txt(c:Context,value:String,size:Float=15f,color:Int=navy,bold:Boolean=false)=
        TextView(c).apply{
            text=value;textSize=size;setTextColor(color)
            gravity=Gravity.CENTER_VERTICAL or Gravity.RIGHT
            typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
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
    fun show(a:Activity) {
        stopPreview()
        val shell=container(a).apply{
            setPadding(dp(a,18),dp(a,10),dp(a,18),dp(a,16))
            setBackgroundColor(light)
        }
        val top=LinearLayout(a).apply {
            layoutDirection=View.LAYOUT_DIRECTION_RTL
            gravity=Gravity.CENTER_VERTICAL
        }
        shell.addView(top)
        top.addView(txt(a,"آلارم",23f,navy,true),LinearLayout.LayoutParams(0,-2,1f))
        val dialog=Dialog(a)
        top.addView(button(a,"✕  بستن"){dialog.dismiss()})
        val scroll=ScrollView(a).apply{isFillViewport=false;isVerticalScrollBarEnabled=false}
        shell.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val content=container(a)
        scroll.addView(content)
        val all=AlarmStore.read(a)
        val solar=all.firstOrNull{it.sunrise}?:AlarmItem(id="sunrise-primary",
            title="آلارم طلوع آفتاب",sunrise=true,enabled=false)
        val solarCard=card(a,content)
        solarCard.addView(txt(a,"☀  آلارم هوشمند طلوع آفتاب",18f,navy,true))
        val location=AlarmStore.location(a)
        solarCard.addView(txt(a,"موقعیت: "+if(location==null)"ثبت نشده" else AlarmStore.locationLabel(a),
            13f,muted))
        if(location!=null){
            val zone=runCatching{ZoneId.of(location.third)}.getOrDefault(ZoneId.systemDefault())
            val today=Instant.now().atZone(zone).toLocalDate()
            val sunrise=SolarMath.sunrise(today,location.first,location.second,zone)
            solarCard.addView(txt(a,"طلوع امروز: "+if(sunrise==null)"در این تاریخ طلوع رخ نمی‌دهد"
                else displayTime(sunrise,zone),13f,muted))
        }
        solarCard.addView(txt(a,"فاصله: "+solar.offset+" دقیقه قبل | صدا: "+
            AlarmSounds.label(solar.sound),13f,muted))
        solarCard.addView(txt(a,"اجرای بعدی: "+nextText(a,solar),13f,muted))
        val solarControls=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
        solarCard.addView(solarControls)
        solarControls.addView(button(a,"تنظیمات"){edit(a,solar,true,dialog)},
            LinearLayout.LayoutParams(0,-2,1f))
        solarControls.addView(button(a,"موقعیت"){dialog.dismiss();locationEditor(a){show(a)}},
            LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(a,6)})
        solarControls.addView(button(a,if(solar.enabled)"فعال ✓" else "خاموش"){ 
            solar.enabled=!solar.enabled
            if(solar.enabled && AlarmStore.location(a)==null) {
                solar.enabled=false
                locationEditor(a){solar.enabled=true;AlarmStore.update(a,solar);show(a)}
            }else{AlarmStore.update(a,solar);show(a)}
            dialog.dismiss()
        },LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(a,6)})
        content.addView(txt(a,"آلارم‌های معمولی",18f,navy,true).apply{
            setPadding(0,dp(a,20),0,dp(a,5))
        })
        all.filterNot{it.sunrise}.forEach{item->
            val block=card(a,content)
            block.addView(txt(a,item.title,17f,navy,true))
            block.addView(txt(a,"%02d:%02d".format(item.hour,item.minute)+
                "  •  "+repeatName(item)+
                "  •  "+if(item.enabled)"فعال" else "غیرفعال",14f,muted))
            block.addView(txt(a,"صدای زنگ: "+AlarmSounds.label(item.sound)+
                "\nنوبت بعدی: "+nextText(a,item),12f,muted))
            val controls=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
            block.addView(controls)
            for(pair in listOf(
                "ویرایش" to {edit(a,item,false,dialog)},
                (if(item.enabled)"خاموش" else "روشن") to {
                    item.enabled=!item.enabled;AlarmStore.update(a,item);dialog.dismiss();show(a)
                },
                "حذف" to {
                    AlertDialog.Builder(a).setTitle("حذف آلارم")
                        .setMessage("«"+item.title+"» حذف شود؟")
                        .setNegativeButton("انصراف",null)
                        .setPositiveButton("حذف"){_,_->AlarmStore.remove(a,item.id);dialog.dismiss();show(a)}
                        .show()
                }
            ))controls.addView(button(a,pair.first){pair.second()},
                LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(a,5)})
        }
        content.addView(button(a,"تقویم شمسی آلارم‌ها"){
            choosePersianDate(a,System.currentTimeMillis()){selected->
                val fresh=AlarmItem(title="آلارم جدید",repeat=0,startDate=selected)
                edit(a,fresh,false,dialog)
            }
        },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(a,12)})
        content.addView(button(a,"＋ افزودن آلارم"){
            edit(a,AlarmItem(title="آلارم جدید"),false,dialog)
        },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(a,12);bottomMargin=dp(a,16)})
        val mgr=a.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if(Build.VERSION.SDK_INT>=31 && !mgr.canScheduleExactAlarms()){
            content.addView(txt(a,"برای اجرای دقیق زنگ، مجوز «آلارم و یادآوری» را فعال کنید.",
                12f,Color.rgb(186,83,55)))
            content.addView(button(a,"تنظیم مجوز آلارم دقیق") {
                runCatching{a.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:"+a.packageName)))}
            })
        }
        if(Build.VERSION.SDK_INT>=34) {
            val nm=a.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if(!nm.canUseFullScreenIntent()){
                content.addView(txt(a,"برای نمایش زنگ روی صفحه قفل، مجوز نمایش تمام‌صفحه را بررسی کنید.",
                    12f,Color.rgb(186,83,55)))
                content.addView(button(a,"تنظیم نمایش تمام‌صفحه") {
                    runCatching {
                        a.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:"+a.packageName)))
                    }
                })
            }
        }
        dialog.setContentView(shell)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((a.resources.displayMetrics.widthPixels*0.95).toInt(),
                (a.resources.displayMetrics.heightPixels*0.88).toInt())
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes=attributes.apply{dimAmount=0.55f;gravity=Gravity.CENTER}
        }
        dialog.setOnDismissListener {stopPreview();stopRecorder(false)}
        dialog.show()
        dialog.window?.setLayout((a.resources.displayMetrics.widthPixels*0.95).toInt(),
            (a.resources.displayMetrics.heightPixels*0.86).toInt())
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
        val p=PersianCalendar().apply{timeInMillis=millis}
        return "%04d/%02d/%02d".format(java.util.Locale.US,p.get(IcuCalendar.YEAR),
            p.get(IcuCalendar.MONTH)+1,p.get(IcuCalendar.DAY_OF_MONTH))
    }
    private fun choosePersianDate(a:Activity,initial:Long,onPick:(Long)->Unit) {
        val p=PersianCalendar().apply{timeInMillis=if(initial>0)initial else System.currentTimeMillis()}
        val row=LinearLayout(a).apply{
            orientation=LinearLayout.HORIZONTAL;layoutDirection=View.LAYOUT_DIRECTION_RTL
            setPadding(dp(a,12),dp(a,10),dp(a,12),dp(a,10))
        }
        val year=NumberPicker(a).apply{minValue=1300;maxValue=1600;value=p.get(IcuCalendar.YEAR)}
        val month=NumberPicker(a).apply{minValue=1;maxValue=12;value=p.get(IcuCalendar.MONTH)+1}
        val day=NumberPicker(a).apply{minValue=1;maxValue=31;value=p.get(IcuCalendar.DAY_OF_MONTH)}
        fun refresh(){
            val probe=PersianCalendar().apply{
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
                val chosen=PersianCalendar().apply{
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
            setSingleLine(true);textSize=17f
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
            repeat.adapter=ArrayAdapter(a,android.R.layout.simple_spinner_dropdown_item,options)
            repeat.setSelection(copy.repeat)
            layout.addView(txt(a,"نوع تکرار",14f,navy,true));layout.addView(repeat)
            val dateButton=button(a,"تاریخ شروع: "+persianDate(a,if(copy.startDate>0)copy.startDate else System.currentTimeMillis())){
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
                hint="تعداد دفعات (خالی = نامحدود)"
                inputType=android.text.InputType.TYPE_CLASS_NUMBER
                setText(if(copy.maxOccurrences>0)copy.maxOccurrences.toString() else "")
            }
            layout.addView(txt(a,"تعداد تکرار",13f,muted))
            layout.addView(count)
            countInput=count
            val lastDay=CheckBox(a).apply{
                text="در ماه کوتاه‌تر، آخرین روز ماه انتخاب شود"
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
                hint="دقیقه دلخواه";inputType=android.text.InputType.TYPE_CLASS_NUMBER or
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
                text=d;buttonTintList=ColorStateList.valueOf(blue)
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
        val vibrate=CheckBox(a).apply{text="لرزش";isChecked=copy.vibrate}
        val gradual=CheckBox(a).apply{text="افزایش تدریجی صدا";isChecked=copy.gradual}
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
        layout.addView(button(a,"دریافت موقعیت فعلی گوشی"){
            locateAfterPermission={
                readLocation(a){latitude,longitude->
                    lat.setText(latitude.toString());lon.setText(longitude.toString())
                    place.setText("موقعیت فعلی")
                }
            }
            if(a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==
                PackageManager.PERMISSION_GRANTED)locateAfterPermission?.invoke()
            else a.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION)
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
    private fun readLocation(a:Activity,callback:(Double,Double)->Unit) {
        val mgr=a.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if(a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=
            PackageManager.PERMISSION_GRANTED)return
        val provider=when{
            mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)->LocationManager.NETWORK_PROVIDER
            mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)->LocationManager.GPS_PROVIDER
            else->null
        }
        if(provider==null){Toast.makeText(a,"مکان‌یابی گوشی خاموش است",Toast.LENGTH_LONG).show();return}
        if(Build.VERSION.SDK_INT>=30){
            mgr.getCurrentLocation(provider,null,a.mainExecutor){loc->
                if(loc!=null)callback(loc.latitude,loc.longitude)
                else Toast.makeText(a,"موقعیت یافت نشد؛ مختصات را دستی وارد کنید",
                    Toast.LENGTH_LONG).show()
            }
        }else{
            val loc=mgr.getLastKnownLocation(provider)
            if(loc!=null)callback(loc.latitude,loc.longitude)
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
