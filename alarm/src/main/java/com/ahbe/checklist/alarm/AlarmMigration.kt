package com.ahbe.checklist.alarm

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/** Import *only* alarm preferences/audio from the notes app's portable ZIP backup. */
object AlarmMigration {
    private const val MAX_TOTAL=250L*1024*1024
    private const val PREF="independent_alarm_settings_v1"
    fun importFromNotesBackup(c:Context,uri:Uri) {
        var total=0L
        var meta:JSONObject?=null
        val recordings=mutableListOf<Pair<String,ByteArray>>()
        c.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry=zip.nextEntry
                var entries=0
                while(entry!=null) {
                    entries++
                    require(entries<=10000){"فایل پشتیبان بیش‌ازحد بزرگ است"}
                    val path=entry.name
                    require(!path.startsWith("/") && !path.contains('\\') &&
                        path.split('/').none{it==".." || it=="."}){"مسیر نامعتبر است"}
                    if(!entry.isDirectory && (path=="backup.json" ||
                            path.startsWith("files/alarm_sounds/"))) {
                        val output=java.io.ByteArrayOutputStream()
                        val buffer=ByteArray(8192)
                        while(true) {
                            val count=zip.read(buffer)
                            if(count<0)break
                            total+=count
                            require(total<=MAX_TOTAL){"فایل بیش‌ازحد بزرگ است"}
                            output.write(buffer,0,count)
                        }
                        val bytes=output.toByteArray()
                        if(path=="backup.json") {
                            require(meta==null && bytes.size<=8*1024*1024){"اطلاعات پشتیبان نامعتبر است"}
                            meta=JSONObject(String(bytes,Charsets.UTF_8))
                        } else {
                            val name=path.removePrefix("files/alarm_sounds/")
                            require(name.matches(Regex("[A-Za-z0-9_.-]+")) &&
                                bytes.size<=30*1024*1024){"صدای آلارم نامعتبر است"}
                            recordings.add(name to bytes)
                        }
                    }
                    zip.closeEntry()
                    entry=zip.nextEntry
                }
            }
        } ?: error("امکان بازکردن فایل وجود ندارد")
        val manifest=meta?:error("اطلاعات پشتیبان پیدا نشد")
        require(manifest.optString("format")=="checklist-user-backup" &&
            manifest.optInt("version")==1){"فرمت پشتیبان پشتیبانی نمی‌شود"}
        val entries=manifest.getJSONObject("preferences")
            .optJSONObject(PREF)?:error("در این فایل آلارمی برای انتقال وجود ندارد")
        val json=entries.optJSONObject("alarms")?.optString("value","[]")?:"[]"
        val parsed=JSONArray(json)
        require(parsed.length()<=2000){"تعداد آلارم‌ها نامعتبر است"}
        for(i in 0 until parsed.length())AlarmItem.from(parsed.getJSONObject(i))
        val existing=AlarmStore.read(c)
        existing.forEach{AlarmScheduler.cancel(c,it.id)}
        val sounds=File(c.filesDir,"alarm_sounds").apply{mkdirs()}
        recordings.forEach{(name,bytes)->
            File(sounds,name).outputStream().use{it.write(bytes)}
        }
        val edit=c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().clear()
        for(key in entries.keys()) {
            val entry=entries.getJSONObject(key)
            when(entry.getString("type")) {
                "string"->edit.putString(key,entry.getString("value"))
                "long"->edit.putLong(key,entry.getLong("value"))
                "boolean"->edit.putBoolean(key,entry.getBoolean("value"))
                "int"->edit.putInt(key,entry.getInt("value"))
                "float"->edit.putFloat(key,entry.getDouble("value").toFloat())
            }
        }
        check(edit.commit()){"تنظیمات ذخیره نشد"}
        AlarmScheduler.restore(c)
    }
}
