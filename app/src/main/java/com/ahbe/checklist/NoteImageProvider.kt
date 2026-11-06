package com.ahbe.checklist

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Read-only URI for opening a private note image in the phone's default viewer. */
class NoteImageProvider : ContentProvider() {
    companion object {
        private val pattern = Regex("^[0-9a-fA-F-]{36}\\.(jpg|png|webp)$")
        fun uri(context: android.content.Context, filename: String): Uri =
            Uri.Builder().scheme("content")
                .authority(context.packageName + ".noteimages")
                .appendPath(filename).build()
    }
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (uri.authority != ctx.packageName + ".noteimages" ||
            uri.pathSegments.size != 1 || !pattern.matches(name))
            throw FileNotFoundException()
        val target = File(File(ctx.filesDir, "note_images"), name)
        if (!target.isFile) throw FileNotFoundException()
        return target
    }
    override fun getType(uri: Uri): String = when (file(uri).extension) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val stored = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> stored.name
                    OpenableColumns.SIZE -> stored.length()
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
