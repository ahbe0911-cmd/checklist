package com.ahbe.checklist

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Write-capable URI only for staged camera photos. Saved note images continue
 * to use the separate, read-only NoteImageProvider.
 */
class CameraCaptureProvider : ContentProvider() {
    companion object {
        private val filenamePattern = Regex("^[0-9a-fA-F-]{36}\\.jpg$")

        fun stagedFile(context: Context, filename: String): File? =
            filename.takeIf { filenamePattern.matches(it) }
                ?.let { File(File(context.cacheDir, "novi_camera"), it) }

        fun uri(context: Context, filename: String): Uri =
            Uri.Builder().scheme("content")
                .authority(context.packageName + ".cameracapture")
                .appendPath(filename).build()
    }

    override fun onCreate(): Boolean = true

    private fun checkedFile(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        if (uri.authority != ctx.packageName + ".cameracapture" ||
            uri.pathSegments.size != 1) throw FileNotFoundException()
        val output = stagedFile(ctx, uri.pathSegments[0]) ?: throw FileNotFoundException()
        if (!output.isFile) throw FileNotFoundException()
        return output
    }

    override fun getType(uri: Uri): String {
        checkedFile(uri)
        return "image/jpeg"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = checkedFile(uri)
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_TRUNCATE
            "rw", "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw FileNotFoundException("Unsupported mode: $mode")
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val image = checkedFile(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { key ->
                when (key) {
                    OpenableColumns.DISPLAY_NAME -> image.name
                    OpenableColumns.SIZE -> image.length()
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
