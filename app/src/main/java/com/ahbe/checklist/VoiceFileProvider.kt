package com.ahbe.checklist

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONArray
import java.io.File
import java.io.FileNotFoundException

/**
 * Shares a private audio file under the name the user entered for the note.
 * The actual private filename stays UUID-based so old files and reminders are unchanged.
 */
class VoiceFileProvider : ContentProvider() {
    companion object {
        private val SAFE_FILE = Regex("^[0-9a-fA-F-]{36}\\.m4a$")

        // Preserve the original title, except characters forbidden in filenames.
        fun exportName(title: String): String {
            val sanitized = title.trim().map { ch ->
                if (ch == '/' || ch == '\\' || ch.code < 32 || ch.code == 127) '_' else ch
            }.joinToString("").trim().trimEnd('.')
            val base = sanitized.ifBlank { "پیام صوتی" }
            return if (base.endsWith(".m4a", ignoreCase = true)) base else base + ".m4a"
        }

        fun titleFor(context: Context, filename: String): String {
            val prefs = context.getSharedPreferences("checklist_native", Context.MODE_PRIVATE)
            val saved = prefs.getString("optimized_notes", "[]") ?: "[]"
            val rows = runCatching { JSONArray(saved) }.getOrNull() ?: return "پیام صوتی"
            for (i in 0 until rows.length()) {
                val note = rows.optJSONObject(i) ?: continue
                if (note.optString("audioFilename") == filename)
                    return note.optString("text").ifBlank { "پیام صوتی" }
                val attachments = note.optJSONArray("audioAttachments") ?: continue
                for (j in 0 until attachments.length()) {
                    val attachment = attachments.optJSONObject(j) ?: continue
                    if (attachment.optString("filename") == filename)
                        return attachment.optString("title").ifBlank { "پیام صوتی" }
                }
            }
            return "پیام صوتی"
        }

        fun uri(context: Context, filename: String): Uri =
            Uri.Builder().scheme("content")
                .authority(context.packageName + ".voicefiles")
                .appendPath(filename)
                .appendPath(exportName(titleFor(context, filename)))
                .build()
    }

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        if (uri.authority != ctx.packageName + ".voicefiles") throw FileNotFoundException()
        val segments = uri.pathSegments
        if (segments.size !in 1..2 || !SAFE_FILE.matches(segments[0]))
            throw FileNotFoundException()
        val file = File(File(ctx.filesDir, "voice_notes"), segments[0])
        if (!file.isFile) throw FileNotFoundException()
        return file
    }

    override fun getType(uri: Uri): String {
        fileFor(uri)
        return "audio/mp4"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val file = fileFor(uri)
        val title = exportName(titleFor(context!!, file.name))
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> title
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        })
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
