package com.sakhand.downloadmanager.engine

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.sakhand.downloadmanager.data.DownloadItem
import java.io.File

/**
 * ذخیره فایل نهایی در پوشه Download عمومی + باز کردن فایل با برنامه مناسب
 */
object StorageSaver {

    /**
     * فایل موقت (.part) را به پوشه دانلود عمومی منتقل می‌کند.
     * اندروید ۱۰+: MediaStore (بدون نیاز به مجوز)
     * اندروید ۸ و ۹: مسیر مستقیم (با مجوز WRITE_EXTERNAL_STORAGE)
     * مقدار برگشتی: content://... یا مسیر فایل
     */
    fun saveToPublicDownloads(context: Context, src: File, displayName: String, mime: String): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw RuntimeException("ساخت فایل در پوشه Download ناموفق بود")
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { input -> input.copyTo(out) }
            } ?: throw RuntimeException("نوشتن فایل در حافظه ناموفق بود")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val dest = uniqueFile(dir, displayName)
            src.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
            dest.absolutePath
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (file.exists()) {
            file = File(dir, "$base ($i)$ext")
            i++
        }
        return file
    }

    /**
     * باز کردن فایل تکمیل‌شده با برنامه مناسب سیستم
     */
    fun openFile(context: Context, item: DownloadItem) {
        try {
            val saved = item.savedUri
            if (saved.isNullOrBlank()) {
                Toast.makeText(context, "مسیر فایل پیدا نشد", Toast.LENGTH_SHORT).show()
                return
            }
            val uri: Uri = if (saved.startsWith("content://")) {
                Uri.parse(saved)
            } else {
                FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    File(saved)
                )
            }
            val mime = item.mime ?: DownloadManager.guessMime(item.fileName)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "برنامه‌ای برای باز کردن این فایل پیدا نشد", Toast.LENGTH_SHORT).show()
        }
    }
}
