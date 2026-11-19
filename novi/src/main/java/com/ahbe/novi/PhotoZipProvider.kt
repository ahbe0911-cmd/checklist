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

/** Grants read-only, temporary access to a user's ZIP via Android Sharesheet. */
class PhotoZipProvider : ContentProvider() {
    private val idPattern = Regex("^[0-9a-fA-F-]{36}\\.zip$")

    override fun onCreate(): Boolean = true

    private fun archive(uri: Uri): Pair<PhotoZipArchive.Archive, File> {
        val ctx = context ?: throw FileNotFoundException()
        if (uri.authority != ctx.packageName + ".photozips") throw FileNotFoundException()
        val parts = uri.pathSegments
        if (parts.size != 2 || !idPattern.matches(parts[0])) throw FileNotFoundException()
        val id = parts[0].removeSuffix(".zip")
        val item = PhotoZipArchive.find(ctx, id) ?: throw FileNotFoundException()
        if (parts[1] != PhotoZipArchive.exportName(item.title)) throw FileNotFoundException()
        val file = PhotoZipArchive.file(ctx, id) ?: throw FileNotFoundException()
        return item to file
    }

    override fun getType(uri: Uri): String {
        archive(uri)
        return "application/zip"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(archive(uri).second,
            ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val (item, file) = archive(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> PhotoZipArchive.exportName(item.title)
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
