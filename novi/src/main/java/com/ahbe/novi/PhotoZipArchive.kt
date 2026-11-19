package com.ahbe.novi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Independent photo-to-ZIP workflow. No changes to optimized_notes or the existing image gallery.
 * Files are private UUIDs; user-facing names are stored in a separate preference file.
 */
object PhotoZipArchive {
    // Decimal KB is intentional: also stays below 495 KiB in file managers.
    const val MAX_JPEG_BYTES = 495_000
    private const val PREF = "photo_zip_archives_v1"
    private const val KEY = "archives"
    private val ID = Regex("^[0-9a-fA-F-]{36}$")

    data class Archive(
        val id: String,
        val noteId: String,
        val title: String,
        val photoCount: Int,
        val bytes: Long,
        val createdAt: Long
    )

    private fun directory(context: Context): File =
        File(context.filesDir, "photo_zip_archives").apply { mkdirs() }

    private fun storedFile(context: Context, id: String): File {
        require(ID.matches(id)) { "Invalid ZIP identifier" }
        return File(directory(context), id + ".zip")
    }

    fun safeTitle(raw: String): String {
        val trimmed = raw.trim().let { if (it.endsWith(".zip", true)) it.dropLast(4) else it }
        val filtered = trimmed.map { ch ->
            if (ch == '/' || ch == '\\' || ch.code < 32 || ch.code == 127) '_' else ch
        }.joinToString("").trim().trimEnd('.').take(95)
        return filtered.ifBlank { "آرشیو عکس‌ها" }
    }

    fun exportName(title: String): String = safeTitle(title) + ".zip"

    private fun load(context: Context): MutableList<Archive> {
        val json = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                if (!ID.matches(id)) return@mapNotNull null
                Archive(
                    id, obj.optString("noteId"), safeTitle(obj.optString("title")),
                    obj.optInt("photoCount", 0), obj.optLong("bytes", 0L),
                    obj.optLong("createdAt", 0L)
                )
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun save(context: Context, items: List<Archive>) {
        val json = JSONArray()
        items.forEach { item ->
            json.put(JSONObject().apply {
                put("id", item.id)
                put("noteId", item.noteId)
                put("title", item.title)
                put("photoCount", item.photoCount)
                put("bytes", item.bytes)
                put("createdAt", item.createdAt)
            })
        }
        check(context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.toString()).commit()) { "Unable to save ZIP metadata" }
    }

    @Synchronized
    fun forNote(context: Context, noteId: String): List<Archive> =
        load(context).filter { it.noteId == noteId && storedFile(context, it.id).isFile }
            .sortedByDescending { it.createdAt }

    @Synchronized
    fun find(context: Context, id: String): Archive? =
        load(context).firstOrNull { it.id == id && storedFile(context, id).isFile }

    @Synchronized
    fun rename(context: Context, id: String, title: String): Boolean {
        val items = load(context)
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        items[index] = items[index].copy(title = safeTitle(title))
        save(context, items)
        return true
    }

    @Synchronized
    fun delete(context: Context, id: String): Boolean {
        val items = load(context)
        if (items.none { it.id == id }) return false
        val file = storedFile(context, id)
        if (file.exists() && !file.delete()) return false
        save(context, items.filterNot { it.id == id })
        return true
    }

    @Synchronized
    fun deleteForNote(context: Context, noteId: String) {
        val items = load(context)
        items.filter { it.noteId == noteId }.forEach { storedFile(context, it.id).delete() }
        save(context, items.filterNot { it.noteId == noteId })
    }

    fun file(context: Context, id: String): File? =
        find(context, id)?.let { storedFile(context, it.id) }

    fun uri(context: Context, id: String): Uri {
        val item = find(context, id) ?: error("ZIP not found")
        return Uri.Builder().scheme("content")
            .authority(context.packageName + ".photozips")
            .appendPath(item.id + ".zip")
            .appendPath(exportName(item.title)).build()
    }

    /**
     * This runs exclusively on a worker thread. An incomplete ZIP never appears in the archive.
     * Each JPEG is checked before being added to the archive.
     */
    fun create(context: Context, noteId: String, name: String, photos: List<Uri>,
               onProgress: (Int, Int) -> Unit = { _, _ -> }): Archive {
        require(photos.isNotEmpty()) { "هیچ عکسی انتخاب نشده است" }
        require(photos.size <= 100) { "حداکثر ۱۰۰ عکس در هر بسته انتخاب کنید" }
        val id = UUID.randomUUID().toString()
        val finalFile = storedFile(context, id)
        val temporary = File(directory(context), id + ".partial")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
                photos.forEachIndexed { index, imageUri ->
                    val bytes = imageToJpeg(context, imageUri)
                    check(bytes.isNotEmpty() && bytes.size <= MAX_JPEG_BYTES) {
                        "حجم JPG از ۴۹۵ کیلوبایت بیشتر شد"
                    }
                    zip.putNextEntry(ZipEntry(zipImageName(context, imageUri, index)))
                    zip.write(bytes)
                    zip.closeEntry()
                    onProgress(index + 1, photos.size)
                }
            }
            require(temporary.length() > 0L) { "فایل ZIP خالی است" }
            if (!temporary.renameTo(finalFile)) {
                temporary.copyTo(finalFile, overwrite = false)
                check(temporary.delete()) { "Cannot remove staging file" }
            }
            val entry = Archive(id, noteId, safeTitle(name), photos.size, finalFile.length(),
                System.currentTimeMillis())
            synchronized(this) {
                val current = load(context)
                current.add(entry)
                save(context, current)
            }
            return entry
        } catch (error: Exception) {
            temporary.delete()
            finalFile.delete()
            throw error
        }
    }

    private fun zipImageName(context: Context, uri: Uri, index: Int): String {
        val selectedName = runCatching {
            context.contentResolver.query(uri,
                arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull().orEmpty()
        val basename = selectedName.substringBeforeLast('.', selectedName)
        val cleaned = basename.map { ch ->
            if (ch == '/' || ch == '\\' || ch.code < 32 || ch.code == 127) '_' else ch
        }.joinToString("").trim().take(60).ifBlank { "photo" }
        return (index + 1).toString().padStart(2, '0') + "_" + cleaned + ".jpg"
    }

    private fun imageToJpeg(context: Context, uri: Uri): ByteArray {
        val source = decodePhoto(context, uri)
        var current = source
        try {
            // Favor a clear, high-quality JPEG over aggressive JPEG artifacts.
            // If 84+ quality cannot fit, reduce dimensions gradually and retry.
            repeat(12) {
                var low = 84
                var high = 98
                var best: ByteArray? = null
                while (low <= high) {
                    val quality = (low + high) / 2
                    val data = encode(current, quality)
                    if (data.size <= MAX_JPEG_BYTES) {
                        best = data
                        low = quality + 1
                    } else high = quality - 1
                }
                if (best != null) return best

                val smallest = encode(current, 84)
                val factor = (sqrt(MAX_JPEG_BYTES.toDouble() / smallest.size) * 0.97)
                    .coerceIn(0.52, 0.90)
                val width = (current.width * factor).roundToInt().coerceAtLeast(1)
                val height = (current.height * factor).roundToInt().coerceAtLeast(1)
                if (width == current.width && height == current.height) {
                    return lowestQualityFallback(current)
                }
                val scaled = Bitmap.createScaledBitmap(current, width, height, true)
                if (scaled !== current) current.recycle()
                current = scaled
            }
            return lowestQualityFallback(current)
        } finally {
            if (!current.isRecycled) current.recycle()
        }
    }

    private fun lowestQualityFallback(bitmap: Bitmap): ByteArray {
        var low = 1
        var high = 83
        var best: ByteArray? = null
        while (low <= high) {
            val quality = (low + high) / 2
            val data = encode(bitmap, quality)
            if (data.size <= MAX_JPEG_BYTES) {
                best = data
                low = quality + 1
            } else high = quality - 1
        }
        return best ?: error("این عکس حتی با کمترین کیفیت زیر ۴۹۵ کیلوبایت قرار نمی‌گیرد")
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream(256 * 1024)
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            "تبدیل JPG انجام نشد"
        }
        return output.toByteArray()
    }

    private fun sampleFor(width: Int, height: Int): Int {
        var sample = 1
        while ((width / sample).toLong() * (height / sample).toLong() > 20_000_000L ||
            maxOf(width / sample, height / sample) > 5200) {
            sample *= 2
        }
        return sample
    }

    private fun decodePhoto(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) {
                    decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(sampleFor(info.size.width, info.size.height))
                }
            }.getOrNull()?.let { return opaque(it) }
        }

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("دسترسی به تصویر ممکن نیست")
        require(options.outWidth > 0 && options.outHeight > 0) {
            "فرمت عکس توسط این نسخه اندروید پشتیبانی نمی‌شود"
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sampleFor(options.outWidth, options.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: error("خواندن تصویر انجام نشد")

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        val oriented = if (matrix.isIdentity) decoded else
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        return opaque(oriented)
    }

    // Transparent PNG pixels should become white, not black, when exported as JPEG.
    private fun opaque(source: Bitmap): Bitmap {
        if (!source.hasAlpha()) return source
        val flattened = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, 0f, 0f, null)
        }
        source.recycle()
        return flattened
    }
}
