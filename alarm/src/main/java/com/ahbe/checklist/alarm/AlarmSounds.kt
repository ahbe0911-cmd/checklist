package com.ahbe.checklist.alarm

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.AudioManager
import android.os.*
import android.os.VibrationEffect
import android.os.Vibrator
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*
import kotlin.random.Random

/** Original programmatically synthesized PCM WAVs: no third-party copyrighted recordings. */
object AlarmSounds {
    val labels=arrayOf("طلوع آرام","صبح دل‌انگیز","پیانوی آرام","زنگ کلاسیک",
        "بیدارباش انرژی‌بخش","نسیم صبحگاهی","زنگ دیجیتال","زنگ مینیمال")
    fun label(path:String)=if(path.startsWith("preset:"))
        labels.getOrElse(path.removePrefix("preset:").toIntOrNull()?:0){labels[0]}
        else File(path).nameWithoutExtension
    fun file(c:Context,id:String):File? {
        if(!id.startsWith("preset:")) {
            val root=File(c.filesDir,"alarm_sounds").canonicalFile
            val file=runCatching {File(id).canonicalFile}.getOrNull()?:return null
            return file.takeIf {it.isFile && it.parentFile==root}
        }
        val preset=(id.removePrefix("preset:").toIntOrNull()?:0).coerceIn(0,7)
        val dir=File(c.filesDir,"alarm_sounds");dir.mkdirs()
        val file=File(dir,"preset_$preset.wav")
        if(file.isFile && file.length()>40000)return file
        val rate=16000
        val n=rate*6
        val notes=arrayOf(
            doubleArrayOf(392.0,440.0,523.25,659.25,523.25,440.0),
            doubleArrayOf(880.0,1174.6,987.8,1318.5,987.8,880.0),
            doubleArrayOf(261.63,329.63,392.0,523.25,392.0,329.63),
            doubleArrayOf(784.0,622.25,784.0,622.25,784.0,622.25),
            doubleArrayOf(523.25,659.25,783.99,1046.5,783.99,1046.5),
            doubleArrayOf(440.0,587.33,659.25,587.33,440.0,329.63),
            doubleArrayOf(659.25,880.0,659.25,880.0,987.77,659.25),
            doubleArrayOf(523.25,0.0,523.25,0.0,659.25,0.0)
        )[preset]
        val data=ByteArray(n*2)
        val random=Random(1024+preset)
        for(i in 0 until n) {
            val t=i.toDouble()/rate
            val k=(t.toInt()).coerceIn(0,5)
            val freq=notes[k]
            val fade=(min(1.0,(t-k)*5)*min(1.0,(k+1-t)*4)).coerceIn(0.0,1.0)
            val sine=if(freq==0.0)0.0 else sin(2*Math.PI*freq*t)
            val bell=0.48*sine+0.18*sin(2*Math.PI*freq*2*t)+0.07*sin(2*Math.PI*freq*3*t)
            val sound=when(preset) {
                1->bell*0.30+0.16*sin(2*Math.PI*(1100+250*sin(2*Math.PI*3*t))*t)*fade
                5->bell*0.24+random.nextDouble(-0.055,0.055)
                3->bell*0.72
                4->bell*0.60
                6->0.48*sin(2*Math.PI*freq*t)
                else->bell*0.44
            }
            val smooth=if(preset==0 || preset==2 || preset==5)
                min(1.0,t/1.4) else 1.0
            val value=(sound*fade*smooth*27000).roundToInt().coerceIn(-32768,32767)
            data[2*i]=(value and 255).toByte()
            data[2*i+1]=((value ushr 8) and 255).toByte()
        }
        runCatching {
            FileOutputStream(file).use { out ->
                fun num(v:Int,bytes:Int){for(i in 0 until bytes)out.write(v ushr (8*i) and 255)}
                out.write("RIFF".toByteArray());num(36+data.size,4)
                out.write("WAVEfmt ".toByteArray());num(16,4);num(1,2)
                num(1,2);num(rate,4);num(rate*2,4);num(2,2);num(16,2)
                out.write("data".toByteArray());num(data.size,4);out.write(data)
            }
        }
        return file.takeIf {it.isFile}
    }
}
class AlarmSoundService:Service() {
    companion object {
        const val START="com.ahbe.checklist.alarm.START"
        const val STOP="com.ahbe.checklist.alarm.STOP"
        private const val CHANNEL="independent_alarm_ring_v1"
        private const val ID=9061
        fun channel(c:Context) {
            val mgr=c.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(NotificationChannel(CHANNEL,"زنگ ساعت",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description="نمایش زنگ ساعت و کنترل توقف یا تعویق"
                lockscreenVisibility=Notification.VISIBILITY_PUBLIC
                enableVibration(false)
            })
        }
        fun notification(c:Context,item:AlarmItem):Notification {
            channel(c)
            val screen=Intent(c,AlarmRingActivity::class.java).apply {
                putExtra("alarm_id",item.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val open=PendingIntent.getActivity(c,item.id.hashCode(),screen,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop=PendingIntent.getService(c,18,
                Intent(c,AlarmSoundService::class.java).apply {action=STOP},
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(c,CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("آلارم: "+item.title)
                .setContentText("برای توقف یا تعویق، زنگ را باز کنید")
                .setContentIntent(open).setFullScreenIntent(open,true)
                .setPriority(Notification.PRIORITY_MAX).setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_pause,"توقف",stop)
                .setOngoing(true).build()
        }
        fun notifyOnly(c:Context,item:AlarmItem) {
            runCatching {
                (c.getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(ID,notification(c,item))
            }
        }
    }
    private var media:MediaPlayer?=null
    private var vibrator:Vibrator?=null
    private val handler=Handler(Looper.getMainLooper())
    private var fade:Runnable?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP) {end();return START_NOT_STICKY}
        if(intent?.action!=START)return START_NOT_STICKY
        val item=AlarmStore.get(this,intent.getStringExtra("alarm_id")?:"")
            ?:return START_NOT_STICKY
        endSound()
        val notification=notification(this,item)
        try {
            if(Build.VERSION.SDK_INT>=29)
                startForeground(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(ID,notification)
        }catch(e:Exception) {
            android.util.Log.e("AlarmSoundService","Foreground playback could not start",e)
            notifyOnly(this,item);stopSelf();return START_NOT_STICKY
        }
        // Sound generation happens before starting the player; a missing custom tone falls back
        // to an installed synthetic tone rather than producing a silent alarm.
        val file=AlarmSounds.file(this,item.sound)?:AlarmSounds.file(this,"preset:3")
        if(file!=null) {
            val candidate=MediaPlayer()
            runCatching {
                candidate.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                candidate.setDataSource(file.absolutePath)
                candidate.isLooping=true
                candidate.prepare()
                candidate.setVolume(if(item.gradual)0.1f else 1f,
                    if(item.gradual)0.1f else 1f)
                candidate.start()
                media=candidate
                if(item.gradual) {
                    var step=1
                    val ticker=object:Runnable {
                        override fun run() {
                            if(media!==candidate)return
                            step=(step+1).coerceAtMost(20)
                            val vol=step/20f
                            candidate.setVolume(vol,vol)
                            if(step<20)handler.postDelayed(this,900L)
                        }
                    }
                    fade=ticker;handler.postDelayed(ticker,900L)
                }
            }.onFailure { failure ->
                android.util.Log.e("AlarmSoundService","Alarm audio playback failed",failure)
                runCatching {candidate.release()}
            }
        }
        if(item.vibrate) {
            vibrator=getSystemService(VIBRATOR_SERVICE) as Vibrator
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0,500,600),0))
        }
        return START_NOT_STICKY
    }
    private fun endSound() {
        fade?.let {handler.removeCallbacks(it)};fade=null
        runCatching {media?.stop()};runCatching {media?.release()};media=null
        vibrator?.cancel();vibrator=null
    }
    private fun end(){
        endSound();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy(){endSound();super.onDestroy()}
}
