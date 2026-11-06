package com.ahbe.checklist

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/** Shares individual saved voice notes read-only through a temporary URI grant. */
class VoiceFileProvider : ContentProvider() {
    companion object {
        private val SAFE_NAME = Regex("^[0-9a-fA-F-]{36}\\.m4a$")
        fun uri(context: android.content.Context, filename: String): Uri =
            Uri.Builder().scheme("content")
                .authority(context.packageName + ".voicefiles")
                .appendPath(filename).build()
    }

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File {
        if (uri.authority != context?.packageName + ".voicefiles") throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (uri.pathSegments.size != 1 || !SAFE_NAME.matches(name)) throw FileNotFoundException()
        val file = File(File(context!!.filesDir, "voice_notes"), name)
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
        val columns = projection ?: arrayOf("_display_name", "_size")
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map {
            when (it) {
                "_display_name" -> file.name
                "_size" -> file.length()
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
