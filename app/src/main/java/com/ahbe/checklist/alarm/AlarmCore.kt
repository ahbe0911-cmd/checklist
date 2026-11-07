package com.ahbe.checklist.alarm

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import android.icu.util.PersianCalendar
import android.icu.util.Calendar as IcuCalendar
import java.util.UUID
import java.util.TimeZone
import kotlin.math.*

/** Alarm data is completely separate from checklist_native / optimized_notes. */
data class AlarmItem(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "آلارم",
    var sunrise: Boolean = false,
    var hour: Int = 7,
    var minute: Int = 0,
    var repeat: Int = 1,  // 0 once, 1 daily, 2 weekdays, 3 weekly, 4 monthly, 5 yearly
    var days: Int = 127,  // Saturday=bit0 ... Friday=bit6
    var offset: Int = 15,
    var enabled: Boolean = true,
    var sound: String = "preset:0",
    var vibrate: Boolean = true,
    var snooze: Int = 10,
    var gradual: Boolean = true,
    var next: Long = 0,
    var startDate: Long = 0, // local date at midnight, epoch millis
    var endDate: Long = 0, // inclusive; zero means no end
    var maxOccurrences: Int = 0, // zero means unlimited
    var firedCount: Int = 0,
    var missingDayLast: Boolean = true
) {
    fun toJson() = JSONObject().apply {
        put("id",id);put("title",title);put("sunrise",sunrise)
        put("hour",hour);put("minute",minute);put("repeat",repeat)
        put("days",days);put("offset",offset);put("enabled",enabled)
        put("sound",sound);put("vibrate",vibrate);put("snooze",snooze)
        put("gradual",gradual);put("next",next)
        put("startDate",startDate);put("endDate",endDate)
        put("maxOccurrences",maxOccurrences);put("firedCount",firedCount)
        put("missingDayLast",missingDayLast)
    }
    companion object {
        fun from(j: JSONObject) = AlarmItem(
            j.optString("id").ifBlank { UUID.randomUUID().toString() },
            j.optString("title","آلارم"),j.optBoolean("sunrise",false),
            j.optInt("hour",7).coerceIn(0,23),j.optInt("minute",0).coerceIn(0,59),
            j.optInt("repeat",1).coerceIn(0,5),j.optInt("days",127).coerceIn(0,127),
            j.optInt("offset",15).coerceIn(-1440,1440),
            j.optBoolean("enabled",true),j.optString("sound","preset:0"),
            j.optBoolean("vibrate",true),j.optInt("snooze",10).coerceIn(1,60),
            j.optBoolean("gradual",true),j.optLong("next",0),
            j.optLong("startDate",0),j.optLong("endDate",0),
            j.optInt("maxOccurrences",0).coerceAtLeast(0),j.optInt("firedCount",0).coerceAtLeast(0),
            j.optBoolean("missingDayLast",true)
        )
    }
}
object AlarmStore {
    private const val PREF = "independent_alarm_settings_v1"
    private fun prefs(c:Context) = c.getSharedPreferences(PREF,Context.MODE_PRIVATE)
    fun read(c:Context): MutableList<AlarmItem> = runCatching {
        val a=JSONArray(prefs(c).getString("alarms","[]"))
        MutableList(a.length()){AlarmItem.from(a.getJSONObject(it))}
    }.getOrDefault(mutableListOf())
    fun save(c:Context, items:List<AlarmItem>) {
        val a=JSONArray();items.forEach { a.put(it.toJson()) }
        prefs(c).edit().putString("alarms",a.toString()).commit()
    }
    fun get(c:Context,id:String)=read(c).firstOrNull { it.id==id }
    fun update(c:Context,item:AlarmItem) {
        val list=read(c);val i=list.indexOfFirst {it.id==item.id}
        if(i<0)list.add(item)else list[i]=item
        save(c,list);AlarmScheduler.schedule(c,item)
    }
    fun remove(c:Context,id:String) {
        AlarmScheduler.cancel(c,id)
        save(c,read(c).filterNot {it.id==id})
    }
    fun location(c:Context):Triple<Double,Double,String>? {
        val p=prefs(c);if(!p.contains("lat"))return null
        return Triple(java.lang.Double.longBitsToDouble(p.getLong("lat",0)),
            java.lang.Double.longBitsToDouble(p.getLong("lon",0)),
            p.getString("zone",TimeZone.getDefault().id) ?: TimeZone.getDefault().id)
    }
    fun locationLabel(c:Context) = prefs(c).getString("place","مختصات ذخیره‌شده") ?: "مختصات ذخیره‌شده"
    fun setLocation(c:Context,lat:Double,lon:Double,zone:String,label:String) {
        require(lat in -90.0..90.0 && lon in -180.0..180.0)
        ZoneId.of(zone)
        prefs(c).edit().putLong("lat",java.lang.Double.doubleToRawLongBits(lat))
            .putLong("lon",java.lang.Double.doubleToRawLongBits(lon))
            .putString("zone",zone).putString("place",label).commit()
        read(c).filter { it.sunrise }.forEach { AlarmScheduler.schedule(c,it) }
    }
}

/** NOAA solar equation of time and declination; no fabricated result on polar days. */
object SolarMath {
    fun sunrise(date:LocalDate, lat:Double, lon:Double, zone:ZoneId):Long? {
        val latitude=Math.toRadians(lat)
        for(delta in -1L..1L) {
            val utc=date.plusDays(delta)
            val day=utc.dayOfYear.toDouble()
            val days=if(utc.isLeapYear)366.0 else 365.0
            val gamma=2.0*Math.PI/days*(day-1)
            val eq=229.18*(0.000075+0.001868*cos(gamma)-0.032077*sin(gamma)
                -0.014615*cos(2*gamma)-0.040849*sin(2*gamma))
            val decl=0.006918-0.399912*cos(gamma)+0.070257*sin(gamma)
                -0.006758*cos(2*gamma)+0.000907*sin(2*gamma)
                -0.002697*cos(3*gamma)+0.00148*sin(3*gamma)
            val cosH=(cos(Math.toRadians(90.833))/(cos(latitude)*cos(decl))
                -tan(latitude)*tan(decl))
            if(!cosH.isFinite() || cosH < -1 || cosH > 1)continue
            val minutes=720.0 - 4*lon - eq - 4*Math.toDegrees(acos(cosH))
            val epoch=utc.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()+
                (minutes*60000).roundToLong()
            if(Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate()==date)return epoch
        }
        return null
    }
}
object AlarmScheduler {
    const val FIRE="com.ahbe.checklist.alarm.FIRE"
    private fun intent(c:Context,id:String,snooze:Boolean=false)=Intent(c,AlarmTriggerReceiver::class.java).apply {
        action=FIRE
        data=Uri.parse("alarmclock://"+(if(snooze)"snooze/" else "main/")+Uri.encode(id))
        putExtra("alarm_id",id)
        putExtra("is_snooze",snooze)
    }
    private fun pending(c:Context,id:String,snooze:Boolean=false,flags:Int=PendingIntent.FLAG_UPDATE_CURRENT) =
        PendingIntent.getBroadcast(c,0,intent(c,id,snooze),flags or PendingIntent.FLAG_IMMUTABLE)
    fun next(item:AlarmItem,c:Context,now:Long=System.currentTimeMillis()):Long? {
        val location=if(item.sunrise)AlarmStore.location(c) else null
        if(item.sunrise && location==null)return null
        val zone=runCatching { ZoneId.of(location?.third?:TimeZone.getDefault().id) }
            .getOrDefault(ZoneId.systemDefault())
        val today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if(!item.sunrise && item.maxOccurrences>0 && item.firedCount>=item.maxOccurrences)return null
        val anchor=if(item.startDate>0) Instant.ofEpochMilli(item.startDate)
            .atZone(zone).toLocalDate() else today
        val end=if(item.endDate>0) Instant.ofEpochMilli(item.endDate)
            .atZone(zone).toLocalDate() else null
        val anchorPersian=PersianCalendar().apply {
            timeInMillis=anchor.atStartOfDay(zone).toInstant().toEpochMilli()
        }
        val anchorDay=anchorPersian.get(IcuCalendar.DAY_OF_MONTH)
        val anchorMonth=anchorPersian.get(IcuCalendar.MONTH)
        val anchorYear=anchorPersian.get(IcuCalendar.YEAR)
        for(day in 0..3700) {
            val date=today.plusDays(day.toLong())
            if(end!=null && date.isAfter(end))break
            if(!item.sunrise && date.isBefore(anchor))continue
            val dow=(date.dayOfWeek.value+1)%7
            if(item.sunrise) {
                if(item.days and (1 shl dow)==0)continue
                val time=SolarMath.sunrise(date,location!!.first,location.second,zone)?:continue
                val ring=time-item.offset*60000L
                if(ring>now+1000L)return ring
            } else {
                val matches=when(item.repeat) {
                    0 -> date==anchor
                    1 -> true
                    2 -> item.days and (1 shl dow)!=0
                    3 -> java.time.temporal.ChronoUnit.DAYS.between(anchor,date)%7L==0L
                    4,5 -> {
                        val pc=PersianCalendar().apply {
                            timeInMillis=date.atStartOfDay(zone).toInstant().toEpochMilli()
                        }
                        val year=pc.get(IcuCalendar.YEAR)
                        val month=pc.get(IcuCalendar.MONTH)
                        val max=pc.getActualMaximum(IcuCalendar.DAY_OF_MONTH)
                        val target=if(item.missingDayLast)anchorDay.coerceAtMost(max) else anchorDay
                        (item.repeat==4 || (month==anchorMonth && year>=anchorYear)) &&
                            (item.repeat!=5 || month==anchorMonth) &&
                            pc.get(IcuCalendar.DAY_OF_MONTH)==target
                    }
                    else -> false
                }
                if(!matches)continue
                val ring=date.atTime(item.hour,item.minute).atZone(zone).toInstant().toEpochMilli()
                if(ring>now+1000L)return ring
            }
        }
        return null
    }
    fun schedule(c:Context,item:AlarmItem) {
        val mgr=c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        mgr.cancel(pending(c,item.id))
        if (!item.enabled) cancel(c,item.id)
        item.next=if(item.enabled)next(item,c)?:0 else 0
        // Persist the computed next-run independently of all note preferences.
        val list=AlarmStore.read(c);val index=list.indexOfFirst {it.id==item.id}
        if(index>=0){list[index]=item;AlarmStore.save(c,list)}
        if(item.next<=0)return
        val fire=pending(c,item.id)
        try {
            val open=PendingIntent.getActivity(c,item.id.hashCode(),
                Intent(c,AlarmRingActivity::class.java).putExtra("alarm_id",item.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if(Build.VERSION.SDK_INT<31 || mgr.canScheduleExactAlarms()) {
                mgr.setAlarmClock(AlarmManager.AlarmClockInfo(item.next,open),fire)
            } else {
                mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,item.next,fire)
            }
        }catch(_:SecurityException) {
            mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,item.next,fire)
        }
    }
    fun cancel(c:Context,id:String) {
        val mgr=c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for(snooze in listOf(false,true)){
            val p=pending(c,id,snooze,PendingIntent.FLAG_NO_CREATE)?:continue
            mgr.cancel(p);p.cancel()
        }
    }
    fun snooze(c:Context,id:String,minutes:Int) {
        val whenAt=System.currentTimeMillis()+minutes.coerceIn(1,60)*60000L
        val mgr=c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val p=pending(c,id,true)
        try {
            if(Build.VERSION.SDK_INT<31 || mgr.canScheduleExactAlarms())
                mgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,whenAt,p)
            else mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,whenAt,p)
        }catch(_:SecurityException){mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,whenAt,p)}
    }
    fun restore(c:Context) { AlarmStore.read(c).forEach { if(it.enabled)schedule(c,it) } }
}
class AlarmTriggerReceiver:BroadcastReceiver() {
    override fun onReceive(c:Context,intent:Intent) {
        if(intent.action!=AlarmScheduler.FIRE)return
        val id=intent.getStringExtra("alarm_id")?:return
        val item=AlarmStore.get(c,id)?:return
        val isSnooze=intent.getBooleanExtra("is_snooze",false)
        // A one-shot alarm becomes inactive after firing; its snooze must still ring.
        if(!item.enabled && !isSnooze)return
        if(!isSnooze) {
            if(!item.sunrise) {
                item.firedCount++
                if(item.repeat==0 || (item.maxOccurrences>0 && item.firedCount>=item.maxOccurrences)){
                    item.enabled=false;item.next=0
                    val list=AlarmStore.read(c)
                    val idx=list.indexOfFirst{it.id==id}
                    if(idx>=0){list[idx]=item;AlarmStore.save(c,list)}
                } else AlarmScheduler.schedule(c,item)
            } else AlarmScheduler.schedule(c,item)
        }
        val ring=Intent(c,AlarmSoundService::class.java).apply {
            action=AlarmSoundService.START;putExtra("alarm_id",id)
        }
        try {
            if(Build.VERSION.SDK_INT>=26)c.startForegroundService(ring) else c.startService(ring)
        }catch(_:Exception) {
            // Alarm is still surfaced by its notification when background start is denied.
            AlarmSoundService.notifyOnly(c,item)
        }
    }
}
class AlarmBootReceiver:BroadcastReceiver() {
    override fun onReceive(c:Context,intent:Intent) {
        if(intent.action in listOf(Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))
            AlarmScheduler.restore(c)
    }
}
