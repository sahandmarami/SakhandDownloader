package com.sakhand.downloadmanager.social

import com.sakhand.downloadmanager.data.SocialPlatform
import com.sakhand.downloadmanager.engine.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * خطای قابل نمایش به کاربر
 */
class SocialException(message: String) : Exception(message)

data class ResolvedMedia(
    val url: String,
    val fileName: String,
    val mime: String
)

/**
 * استخراج لینک مستقیم رسانه از شبکه‌های اجتماعی
 *
 * - پینترست: مستقیم از متاتگ‌های صفحه (og:image / og:video)
 * - یوتیوب و اینستاگرام: از طریق سرویس استخراج کوبالت (Cobalt)
 */
object SocialResolver {

    // ------------------------------------------------------------
    // تنظیمات سرور استخراج
    // اگر سرور شخصی راه انداختی (طبق README) فقط این دو خط را عوض کن
    // ------------------------------------------------------------
    private const val COBALT_ENDPOINT = "https://api.cobalt.tools/"
    private const val COBALT_API_KEY = "" // مثال: "abc123" — خالی باشد ارسال نمی‌شود

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(
        link: String,
        quality: String,
        audioOnly: Boolean,
        platform: SocialPlatform
    ): ResolvedMedia = withContext(Dispatchers.IO) {
        when (platform) {
            SocialPlatform.PINTEREST -> resolvePinterest(link)
            else -> resolveViaCobalt(link, quality, audioOnly, platform)
        }
    }

    // ------------------------------------------------------------
    // پینترست — دانلود مستقیم بدون واسطه
    // ------------------------------------------------------------

    private fun resolvePinterest(link: String): ResolvedMedia {
        val html = fetchText(link)
        val video = extractMeta(html, "og:video")
            ?: extractMeta(html, "og:video:secure_url")
        val image = extractMeta(html, "og:image")
            ?: extractMeta(html, "og:image:secure_url")
            ?: extractMeta(html, "twitter:image")

        val isVideo = video != null
        val media = video ?: image
            ?: throw SocialException("نتوانستم رسانه این پین را پیدا کنم — مطمئن شو لینک یک پین عمومی است")

        val url = media.replace("&amp;", "&")
        val pinId = Regex("\\d{6,}").find(url)?.value ?: "${System.currentTimeMillis()}"
        val fileName = if (isVideo) "pinterest_$pinId.mp4" else "pinterest_$pinId.jpg"
        return ResolvedMedia(url, fileName, if (isVideo) "video/mp4" else "image/jpeg")
    }

    private fun extractMeta(html: String, property: String): String? {
        val patterns = listOf(
            Regex("""<meta[^>]+property=["']$property["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]*property=["']$property["']""", RegexOption.IGNORE_CASE)
        )
        patterns.forEach { re -> re.find(html)?.let { return it.groupValues[1] } }
        return null
    }

    // ------------------------------------------------------------
    // یوتیوب و اینستاگرام — از طریق سرویس کوبالت
    // ------------------------------------------------------------

    private fun resolveViaCobalt(
        link: String,
        quality: String,
        audioOnly: Boolean,
        platform: SocialPlatform
    ): ResolvedMedia {
        val body = JSONObject().apply {
            put("url", link)
            put("videoQuality", quality) // max / 1080 / 720 / 480
            put("downloadMode", if (audioOnly) "audio" else "auto")
            put("filenameStyle", "pretty")
            put("youtubeVideoCodec", "h264")
        }.toString().toRequestBody("application/json".toMediaType())

        val builder = Request.Builder()
            .url(COBALT_ENDPOINT)
            .header("Accept", "application/json")
            .header("User-Agent", DownloadManager.USER_AGENT)
            .post(body)
        if (COBALT_API_KEY.isNotBlank()) {
            builder.header("Authorization", "Api-Key $COBALT_API_KEY")
        }

        try {
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) throw SocialException("پاسخی از سرور استخراج دریافت نشد")
                val json = JSONObject(text)
                val status = json.optString("status")
                when (status) {
                    "tunnel", "redirect", "stream" -> {
                        val url = json.getString("url")
                        val filename = json.optString("filename").ifBlank {
                            defaultName(platform, audioOnly)
                        }
                        val mime = json.optString("mime").ifBlank {
                            if (audioOnly) "audio/mp4" else "video/mp4"
                        }
                        return ResolvedMedia(url, filename, mime)
                    }
                    "local-processing" -> throw SocialException(
                        "این ویدیو به پردازش خاصی نیاز دارد — کیفیت یا گزینه دیگری را امتحان کن"
                    )
                    "error" -> throw SocialException(errorFa(json))
                    else -> throw SocialException("پاسخ نامعتبر از سرور استخراج (کد ${resp.code})")
                }
            }
        } catch (e: SocialException) {
            throw e
        } catch (e: Exception) {
            throw SocialException("ارتباط با سرور استخراج برقرار نشد — اینترنت را بررسی کن")
        }
    }

    private fun errorFa(json: JSONObject): String {
        val errObj = json.optJSONObject("error")
        val code = errObj?.optString("code") ?: json.optString("error")
        return when (code) {
            "api.auth.key.missing",
            "api.auth.jwt.missing",
            "api.auth.jwt.invalid" -> "سرور عمومی استخراج فعلاً به کلید دسترسی نیاز دارد — طبق راهنمای README یک سرور شخصی تنظیم کن یا بعداً دوباره امتحان کن"

            "link.unsupported" -> "این لینک توسط سرویس استخراج پشتیبانی نمی‌شود"
            "link.invalid" -> "لینک واردشده معتبر نیست"
            "content.video.unavailable" -> "ویدیو در دسترس نیست (خصوصی یا حذف‌شده)"
            "content.video.age" -> "ویدیوهای با محدودیت سنی پشتیبانی نمی‌شوند"
            "content.post.private" -> "این پست خصوصی است و قابل دانلود نیست"
            "content.instagram.auth_required" -> "اینستاگرام برای این لینک احراز هویت می‌خواهد — لینک دیگری امتحان کن"
            "rate_exceeded" -> "تعداد درخواست‌ها زیاد است — چند لحظه بعد دوباره امتحان کن"
            else -> "دریافت ویدیو ناموفق بود (${if (code.isBlank()) "خطای ناشناخته" else code})"
        }
    }

    private fun defaultName(platform: SocialPlatform, audioOnly: Boolean): String {
        val ts = System.currentTimeMillis()
        val p = when (platform) {
            SocialPlatform.YOUTUBE -> "youtube"
            SocialPlatform.INSTAGRAM -> "instagram"
            SocialPlatform.PINTEREST -> "pinterest"
        }
        return "${p}_$ts.${if (audioOnly) "m4a" else "mp4"}"
    }

    // ------------------------------------------------------------

    private fun fetchText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DownloadManager.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw SocialException("دسترسی به صفحه ممکن نشد (کد ${resp.code})")
            }
            return resp.body?.string()
                ?: throw SocialException("محتوای صفحه خالی بود")
        }
    }
}
