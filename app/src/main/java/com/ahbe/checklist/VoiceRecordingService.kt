package com.ahbe.checklist

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import java.io.File
import java.util.UUID

/**
 * Foreground microphone capture continues after the user locks the screen.
 * Recording MUST be initiated by the user while an Activity is visible.
 * Android prohibits silently starting microphone capture in the background.
 */
class VoiceRecordingService : Service() {
    companion object {
        const val ACTION_START = "com.ahbe.checklist.voice.START"
        const val ACTION_STOP = "com.ahbe.checklist.voice.STOP"
        const val ACTION_CANCEL = "com.ahbe.checklist.voice.CANCEL"
        const val PREFS = "voice_recording_state"
        private const val CHANNEL = "voice_recording_v1"
        private const val NOTIFICATION_ID = 702

        @Volatile var isRecording = false
            private set
        @Volatile var recordingStartedAt = 0L
            private set
    }

    private var recorder: MediaRecorder? = null
    private var filename: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "ضبط یادداشت صوتی", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "نمایش وضعیت ضبط و امکان توقف از اعلان" })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!isRecording) startRecording()
            ACTION_STOP -> finishRecording(true)
            ACTION_CANCEL -> finishRecording(false)
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val directory = File(filesDir, "voice_notes")
        if (!directory.exists() && !directory.mkdirs()) {
            stopSelf()
            return
        }
        val name = UUID.randomUUID().toString() + ".m4a"
        val output = File(directory, name)
        var candidate: MediaRecorder? = null
        try {
            val notification = recordingNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            candidate = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            candidate.setAudioSource(MediaRecorder.AudioSource.MIC)
            candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            candidate.setAudioEncodingBitRate(64000)
            candidate.setAudioSamplingRate(44100)
            candidate.setOutputFile(output.absolutePath)
            candidate.prepare()
            candidate.start()
            recorder = candidate
            filename = name
            recordingStartedAt = SystemClock.elapsedRealtime()
            isRecording = true
        } catch (_: Exception) {
            runCatching { candidate?.release() }
            output.delete()
            isRecording = false
            filename = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun recordingNotification(): Notification {
        val stop = Intent(this, VoiceRecordingService::class.java).apply { action = ACTION_STOP }
        val stopIntent = PendingIntent.getService(
            this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("در حال ضبط یادداشت صوتی")
            .setContentText("ضبط حتی با صفحه قفل ادامه دارد. برای پایان، توقف را بزنید.")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "توقف و ذخیره", stopIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun finishRecording(keep: Boolean) {
        val current = recorder
        val name = filename
        val duration = (SystemClock.elapsedRealtime() - recordingStartedAt).coerceAtLeast(0L)
        recorder = null
        filename = null
        isRecording = false
        recordingStartedAt = 0L
        val success = runCatching { current?.stop() }.isSuccess
        runCatching { current?.release() }
        val file = name?.let { File(File(filesDir, "voice_notes"), it) }
        if (keep && success && file != null && file.isFile && file.length() >= 512L) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("draft_filename", name)
                .putLong("draft_duration", duration)
                .commit()
        } else {
            file?.delete()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (recorder != null) finishRecording(true)
        super.onDestroy()
    }
}
