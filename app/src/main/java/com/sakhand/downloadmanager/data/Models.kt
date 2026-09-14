package com.sakhand.downloadmanager.data

/**
 * وضعیت هر دانلود در چرخه حیات
 */
enum class DownloadStatus {
    QUEUED, CONNECTING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELED
}

/**
 * پلتفرم‌های اجتماعی پشتیبانی‌شده
 */
enum class SocialPlatform(val label: String) {
    YOUTUBE("یوتیوب"),
    INSTAGRAM("اینستاگرام"),
    PINTEREST("پینترست")
}

/**
 * مدل یک دانلود
 */
data class DownloadItem(
    val id: String,
    val url: String,
    val fileName: String,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val speedBps: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isSocial: Boolean = false,
    val platform: String? = null,
    val mime: String? = null,
    val savedUri: String? = null
) {
    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED ||
                status == DownloadStatus.CONNECTING ||
                status == DownloadStatus.DOWNLOADING
}

/**
 * وضعیت یک بخش (chunk) از فایل برای دانلود چندتردی
 */
class ChunkState(val start: Long, val end: Long, var downloaded: Long = 0) {

    var done: Boolean = false

    val size: Long get() = if (end >= start) end - start + 1 else -1

    val isComplete: Boolean get() = size in 1..downloaded
}
