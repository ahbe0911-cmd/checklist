package com.ahbe.checklist

import android.content.Context
import android.net.Uri
import com.ahbe.checklist.alarm.AlarmStore
import com.ahbe.checklist.alarm.AlarmScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable backup of app preferences and user-created files. No application logic is modified. */
object UserBackup {
    private const val VERSION = 1
    private const val MAX_BYTES = 250L * 1024 * 1024
    private fun prefsDir(c:Context) = File(c.applicationInfo.dataDir, "shared_prefs")

    fun export(c:Context, uri:Uri) {
        val prefNames = prefsDir(c).listFiles()?.filter { it.isFile && it.extension=="xml" }
            ?.map { it.name.removeSuffix(".xml") } ?: emptyList()
        val preferences=JSONObject()
        for(name in prefNames) {
            val entries=JSONObject()
            for((key,value) in c.getSharedPreferences(name,Context.MODE_PRIVATE).all) {
                val item=JSONObject()
                when(value) {
                    is String -> {item.put("type","string");item.put("value",value)}
                    is Boolean -> {item.put("type","boolean");item.put("value",value)}
                    is Int -> {item.put("type","int");item.put("value",value)}
                    is Long -> {item.put("type","long");item.put("value",value)}
                    is Float -> {item.put("type","float");item.put("value",value.toDouble())}
                    is Set<*> -> {
                        item.put("type","strings")
                        item.put("value",JSONArray(value.filterIsInstance<String>()))
                    }
                    else -> continue
                }
                entries.put(key,item)
            }
            preferences.put(name,entries)
        }
        val files=mutableListOf<Pair<String,File>>()
        fun collect(dir:File,prefix:String) {
            dir.listFiles()?.forEach { file ->
                if(file.isDirectory)collect(file,"$prefix${file.name}/")
                else if(file.isFile)files.add("$prefix${file.name}" to file)
            }
        }
        collect(c.filesDir,"files/")
        val manifest=JSONObject().put("format","checklist-user-backup")
            .put("version",VERSION).put("preferences",preferences)
        c.contentResolver.openOutputStream(uri,"w")?.use { stream ->
            ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(ZipEntry("backup.json"))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for((path,file) in files) {
                    zip.putNextEntry(ZipEntry(path))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error("Cannot write backup")
    }

    fun restore(c:Context,uri:Uri) {
        // Read and validate the entire archive before changing any saved data.
        val staged=File(c.cacheDir,"restore_${System.nanoTime()}").apply { mkdirs() }
        try {
            var total=0L
            var manifest:JSONObject?=null
            val extracted=mutableListOf<Pair<String,File>>()
            c.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zip ->
                    var entry=zip.nextEntry
                    var count=0
                    while(entry!=null) {
                        count++
                        require(count<=10000) {"Too many files"}
                        val name=entry.name
                        require(!name.startsWith("/") && !name.contains("\\") &&
                            name.split('/').none { it==".." || it=="." }) {"Invalid path"}
                        if(!entry.isDirectory) {
                            val out=File(staged,"item_$count")
                            out.outputStream().use { target ->
                                val buffer=ByteArray(8192)
                                while(true) {
                                    val n=zip.read(buffer)
                                    if(n<0)break
                                    total+=n
                                    require(total<=MAX_BYTES) {"Backup too large"}
                                    target.write(buffer,0,n)
                                }
                            }
                            if(name=="backup.json") {
                                require(manifest==null) {"Duplicate manifest"}
                                manifest=JSONObject(out.readText(Charsets.UTF_8))
                            } else {
                                require(name.startsWith("files/")) {"Unexpected archive entry"}
                                extracted.add(name.removePrefix("files/") to out)
                            }
                        }
                        zip.closeEntry()
                        entry=zip.nextEntry
                    }
                }
            } ?: error("Cannot read backup")
            val meta=manifest ?: error("Missing backup information")
            require(meta.optString("format")=="checklist-user-backup" &&
                meta.optInt("version")==VERSION) {"Unsupported backup"}
            val prefs=meta.getJSONObject("preferences")
            val names=prefs.keys().asSequence().toList()
            require(names.all { it.matches(Regex("[A-Za-z0-9_.-]+")) }) {"Invalid preferences"}
            for(name in names) {
                val entries=prefs.getJSONObject(name)
                for(key in entries.keys()) {
                    val item=entries.getJSONObject(key)
                    require(item.getString("type") in listOf(
                        "string","boolean","int","long","float","strings"))
                }
            }
            // Preserve the existing alarm schedule until the replacement is validated.
            AlarmStore.read(c).forEach { AlarmScheduler.cancel(c,it.id) }
            for(name in names) {
                val entries=prefs.getJSONObject(name)
                val editor=c.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear()
                for(key in entries.keys()) {
                    val item=entries.getJSONObject(key)
                    when(item.getString("type")) {
                        "string" -> editor.putString(key,item.getString("value"))
                        "boolean" -> editor.putBoolean(key,item.getBoolean("value"))
                        "int" -> editor.putInt(key,item.getInt("value"))
                        "long" -> editor.putLong(key,item.getLong("value"))
                        "float" -> editor.putFloat(key,item.getDouble("value").toFloat())
                        "strings" -> {
                            val a=item.getJSONArray("value")
                            editor.putStringSet(key,(0 until a.length()).map {
                                a.getString(it)
                            }.toSet())
                        }
                    }
                }
                check(editor.commit()) {"Unable to restore settings"}
            }
            for((name,source) in extracted) {
                require(name.isNotBlank()) {"Invalid file"}
                val dest=File(c.filesDir,name)
                val root=c.filesDir.canonicalPath+File.separator
                require(dest.canonicalPath.startsWith(root)) {"Unsafe file"}
                dest.parentFile?.mkdirs()
                source.copyTo(dest,overwrite=true)
            }
            AlarmStore.read(c).filter { it.enabled }.forEach { AlarmScheduler.schedule(c,it) }
        } finally {
            staged.deleteRecursively()
        }
    }
}
