package com.sakhand.downloadmanager.util

/**
 * ابزارهای قالب‌بندی اعداد و حجم به سبک فارسی
 */

private val FA_DIGITS = mapOf(
    '0' to '۰', '1' to '۱', '2' to '۲', '3' to '۳', '4' to '۴',
    '5' to '۵', '6' to '۶', '7' to '۷', '8' to '۸', '9' to '۹',
    '.' to '٫', ',' to '٬'
)

fun toFa(value: Long): String = value.toString().map { FA_DIGITS[it] ?: it }.joinToString("")

fun toFa(value: Int): String = toFa(value.toLong())

fun String.toFaDigits(): String = map { FA_DIGITS[it] ?: it }.joinToString("")

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "نامشخص"
    if (bytes == 0L) return "۰ بایت"
    val b = bytes.toDouble()
    return when {
        b >= 1024.0 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f گیگابایت", b / (1024.0 * 1024 * 1024))
        b >= 1024.0 * 1024 -> String.format(java.util.Locale.US, "%.1f مگابایت", b / (1024.0 * 1024))
        b >= 1024.0 -> String.format(java.util.Locale.US, "%.1f کیلوبایت", b / 1024.0)
        else -> "$bytes بایت"
    }.toFaDigits()
}

fun formatSpeed(bps: Long): String {
    if (bps <= 0) return "—"
    return formatBytes(bps) + " بر ثانیه"
}

fun etaText(remainingBytes: Long, bps: Long): String {
    if (bps <= 0 || remainingBytes <= 0) return "—"
    val sec = remainingBytes / bps
    return when {
        sec < 60 -> "حدود ${toFa(sec)} ثانیه"
        sec < 3600 -> "حدود ${toFa(sec / 60)} دقیقه"
        else -> "حدود ${toFa(sec / 3600)} ساعت"
    }
}
