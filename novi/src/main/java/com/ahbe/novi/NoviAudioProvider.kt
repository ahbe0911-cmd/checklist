package com.ahbe.novi

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Temporary read-only audio sharing; only files stored by Novi can be opened. */
class NoviAudioProvider : ContentProvider() {
    companion object {
        private val SAFE = Regex("^[0-9a-fA-F-]{36}\\.m4a$")
        fun uri(ctx: android.content.Context, name: String, title: String): Uri =
            Uri.Builder().scheme("content").authority(ctx.packageName + ".audiofiles")
                .appendPath(name).appendPath(title.replace('/', '_').take(80) + ".m4a").build()
    }
    override fun onCreate() = true
    private fun target(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        if (uri.authority != ctx.packageName + ".audiofiles" ||
            uri.pathSegments.size != 2 || !SAFE.matches(uri.pathSegments[0]))
            throw FileNotFoundException()
        val filename = uri.pathSegments[0]
        val isReferenced = NoviStore.load(ctx).any { chat ->
            chat.messages.any { it.kind == "audio" && it.fileId == filename }
        }
        if (!isReferenced) throw FileNotFoundException()
        val file = File(File(ctx.filesDir, "novi_audio"), filename)
        if (!file.isFile) throw FileNotFoundException()
        return file
    }
    override fun getType(uri: Uri): String { target(uri); return "audio/mp4" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(target(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = target(uri)
        val name = uri.pathSegments[1]
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
