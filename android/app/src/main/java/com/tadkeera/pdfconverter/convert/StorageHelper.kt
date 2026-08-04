package com.tadkeera.pdfconverter.convert

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.tadkeera.pdfconverter.util.TextUtils
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Manages the output destination: a folder named "PDF CONVERTER" in the
 * device's local storage.
 *
 *  - Android 10+ (API 29+): MediaStore.Downloads with RELATIVE_PATH
 *    "Download/PDF CONVERTER" — zero permissions, the folder appears in
 *    the user's Downloads folder immediately.
 *  - Android 9 and below (API ≤ 28): the same folder written directly
 *    with the WRITE_EXTERNAL_STORAGE permission.
 */
object StorageHelper {

    const val FOLDER_NAME = "PDF CONVERTER"

    sealed class Sink(val displayPath: String) {
        class LegacyFile(val file: File, displayPath: String) : Sink(displayPath)
        class MediaStoreUri(val uri: Uri, displayPath: String) : Sink(displayPath)
    }

    /** Build the destination sink for `fileName` inside the PDF CONVERTER folder. */
    fun createSink(context: Context, fileName: String): Sink? {
        val safe = TextUtils.sanitizeFileName(fileName, fallback = "output")
        val finalName = if (safe.endsWith(".xlsx")) safe else "$safe.xlsx"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                )
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER_NAME
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values)
                ?: return null
            Sink.MediaStoreUri(
                uri,
                "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME/$finalName"
            )
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FOLDER_NAME
            )
            if (!dir.exists() && !dir.mkdirs()) return null
            Sink.LegacyFile(File(dir, finalName), "${dir.absolutePath}/$finalName")
        }
    }

    fun openOutput(context: Context, sink: Sink): OutputStream? = when (sink) {
        is Sink.LegacyFile -> FileOutputStream(sink.file)
        is Sink.MediaStoreUri -> context.contentResolver.openOutputStream(sink.uri)
    }

    /** After MediaStore writes are finished, publish them (IS_PENDING = 0). */
    fun markPublished(context: Context, sink: Sink) {
        if (sink is Sink.MediaStoreUri) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            runCatching { context.contentResolver.update(sink.uri, values, null, null) }
        }
    }

    /** Remove a partially-written file on failure. */
    fun deleteSink(context: Context, sink: Sink) {
        when (sink) {
            is Sink.LegacyFile -> runCatching { sink.file.delete() }
            is Sink.MediaStoreUri -> runCatching { context.contentResolver.delete(sink.uri, null, null) }
        }
    }
}
