package com.ahbe.checklist.alarm

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Dedicated launcher: no note UI or note data is included in this application. */
class AlarmHomeActivity: Activity() {
    private val runtimeCode=8401
    private val importCode=8402
    private var runtimeRequestInFlight=false
    private var requestedRuntimeOnce=false
    private var requestedExactOnce=false
    private var requestedFullscreenOnce=false
    private var launched=false
    private lateinit var hint:TextView

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val shell=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            layoutDirection=View.LAYOUT_DIRECTION_RTL
            gravity=Gravity.CENTER
            setPadding(28,32,28,32)
            setBackgroundColor(Color.rgb(246,248,252))
        }
        fun label(value:String,size:Float):TextView = TextView(this).apply {
            text=value
            textSize=size
            setTextColor(Color.rgb(30,41,59))
            gravity=Gravity.CENTER
            setPadding(15,15,15,15)
        }
        shell.addView(label("آلارم",30f).apply{typeface=Typeface.DEFAULT_BOLD})
        hint=label("برای زنگ‌زدن دقیق، مجوزهای موردنیاز را فعال کنید.",16f)
        shell.addView(hint)
        shell.addView(label("مدیریت آلارم‌ها",18f).apply{
            setTextColor(Color.rgb(50,92,194))
            setOnClickListener {if(isReady())AlarmUi.show(this@AlarmHomeActivity) else proceed(true)}
        })
        shell.addView(label("فعال‌کردن مجوزها",18f).apply{
            setTextColor(Color.rgb(50,92,194))
            setOnClickListener{proceed(true)}
        })
        shell.addView(label("واردکردن آلارم‌ها از پشتیبان یادداشت‌های من",16f).apply {
            setTextColor(Color.rgb(50,92,194))
            setOnClickListener{
                if(!isReady()){proceed(true);return@setOnClickListener}
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type="application/zip"
                },importCode)
            }
        })
        setContentView(shell)
        shell.post {proceed(true)}
    }

    private fun missingRuntime():List<String> {
        val required=mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.RECORD_AUDIO)
        if(Build.VERSION.SDK_INT>=33)required.add(Manifest.permission.POST_NOTIFICATIONS)
        return required.filter {checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
    }

    private fun missingExact():Boolean {
        val am=getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return Build.VERSION.SDK_INT>=31 && !am.canScheduleExactAlarms()
    }

    private fun missingFullscreen():Boolean {
        val nm=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return Build.VERSION.SDK_INT>=34 && !nm.canUseFullScreenIntent()
    }

    private fun isReady():Boolean =
        missingRuntime().isEmpty() && !missingExact() && !missingFullscreen()

    private fun proceed(userAction:Boolean) {
        if(runtimeRequestInFlight || isFinishing || isDestroyed)return
        val missing=missingRuntime()
        if(missing.isNotEmpty()) {
            hint.text="مجوزهای اعلان، موقعیت دقیق و میکروفون لازم‌اند. بدون تأیید، آلارم اجرا نمی‌شود."
            if(userAction) {
                if(requestedRuntimeOnce &&
                    missing.any{!shouldShowRequestPermissionRationale(it)}) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:"+packageName)))
                } else {
                    requestedRuntimeOnce=true
                    runtimeRequestInFlight=true
                    requestPermissions(missing.toTypedArray(),runtimeCode)
                }
            }
            return
        }
        if(missingExact()) {
            hint.text="مجوز «آلارم و یادآوری» باید برای اجرای دقیق زنگ فعال شود."
            if(userAction && !requestedExactOnce) {
                requestedExactOnce=true
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:"+packageName)))
                }.onFailure{openApplicationSettings()}
            }
            return
        }
        if(missingFullscreen()) {
            hint.text="اجازه نمایش زنگ تمام‌صفحه روی صفحه قفل را فعال کنید."
            if(userAction && !requestedFullscreenOnce) {
                requestedFullscreenOnce=true
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:"+packageName)))
                }.onFailure{openApplicationSettings()}
            }
            return
        }
        hint.text="مجوزها فعال‌اند؛ آلارم‌ها آماده زمان‌بندی دقیق هستند."
        AlarmScheduler.restore(this)
        if(!launched) {
            launched=true
            AlarmUi.show(this)
            AlarmUi.refreshAutomaticLocation(this) {
                if(!isFinishing && !isDestroyed)AlarmUi.show(this)
            }
        }
    }

    private fun openApplicationSettings(){
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:"+packageName)))
    }

    override fun onResume(){
        super.onResume()
        if(::hint.isInitialized)hint.post{proceed(false)}
    }

    override fun onRequestPermissionsResult(requestCode:Int,
        permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==runtimeCode) {
            runtimeRequestInFlight=false
            proceed(grantResults.isNotEmpty() &&
                grantResults.all{it==PackageManager.PERMISSION_GRANTED})
        } else if(requestCode==AlarmUi.LOCATION_PERMISSION) {
            AlarmUi.onLocationPermissionResult(grantResults.isNotEmpty() &&
                grantResults.all{it==PackageManager.PERMISSION_GRANTED})
        } else if(requestCode==AlarmUi.MIC_PERMISSION) {
            AlarmUi.onMicPermissionResult(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)
        }
    }

    @Deprecated("System document picker")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==AlarmUi.PICK_AUDIO){
            if(resultCode==RESULT_OK)AlarmUi.receivePickedAudio(this,data)
        }else if(requestCode==importCode && resultCode==RESULT_OK) {
            val uri=data?.data?:return
            importAlarmBackup(uri)
        }
    }

    private fun importAlarmBackup(uri:Uri) {
        Thread {
            val result=runCatching{AlarmMigration.importFromNotesBackup(this,uri)}
            runOnUiThread {
                if(result.isSuccess) {
                    AlarmScheduler.restore(this)
                    Toast.makeText(this,"آلارم‌های قبلی وارد شدند",Toast.LENGTH_LONG).show()
                    AlarmUi.show(this)
                } else {
                    Toast.makeText(this,"خطا در انتقال آلارم‌ها: "+
                        result.exceptionOrNull()?.message,Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
