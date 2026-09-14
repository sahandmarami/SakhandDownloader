package com.sakhand.downloadmanager.social

import com.sakhand.downloadmanager.data.SocialPlatform
import com.sakhand.downloadmanager.engine.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
 * - پینترست: مستقیم و بدون واسطه (API رسمی صفحه + تحلیل HTML)
 * - یوتیوب و اینستاگرام: از طریق سرویس کوبالت با چند سرور پشتیبان
 *   (اگر سروری از کار افتاد، خودکار سرور بعدی امتحان می‌شود)
 */
object SocialResolver {

    // ------------------------------------------------------------
    // تنظیمات سرور شخصی (اختیاری)
    // اگر سرور خودت را راه انداختی (طبق README) اینجا را پر کن
    // ------------------------------------------------------------
    private const val CUSTOM_ENDPOINT = ""
    private const val CUSTOM_API_KEY = ""

    /**
     * لیست سرورهای عمومی کوبالت — به‌صورت «همزمان» امتحان می‌شوند؛
     * اولین سروری که جواب موفق بدهد برنده است (نیازی به نوبت نیست)
     */
    private val cobaltEndpoints = listOf(
        "https://dwnld.nichind.dev/",
        "https://cobalt-api.kwiatekmiki.com/",
        "https://nyc1.coapi.ggtyler.dev/",
        "https://cobalt.255.one/",
        "https://api.dl.ixhby.dev/"
    )

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"

    /** کلاینت عمومی برای دریافت صفحات (پینترست) */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** کلاینت سریع برای سرورهای کوبالت — سرور مرده نباید بیش از چند ثانیه معطل کند */
    private val cobaltClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
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

    // ============================================================
    // پینترست — دانلود مستقیم بدون واسطه
    // ============================================================

    private fun resolvePinterest(link: String): ResolvedMedia {
        val (html, finalUrl) = fetchPage(link)
        val pinId = Regex("\\d{8,}").find(finalUrl)?.value
            ?: Regex("\\d{8,}").find(html.take(20000))?.value
        val ts = System.currentTimeMillis()
        fun video(name: String, url: String) = ResolvedMedia(url, name, "video/mp4")

        // ۱) API رسمی پین — دقیق‌ترین منبع برای تشخیص ویدیو
        var pinResourceImage: ResolvedMedia? = null
        if (pinId != null) {
            val media = tryPinResource(pinId)
            if (media != null && media.mime == "video/mp4") return media
            pinResourceImage = media
        }

        // ۲) ویدیو داخل خود صفحه (لینک‌های v.pinimg.com .mp4)
        htmlVideo(html)?.let { return video("pinterest_${pinId ?: ts}.mp4", it) }

        // ۳) متاتگ og:video (فقط اگر واقعاً ویدیو باشد، نه عکس)
        ogVideo(html)?.let { return video("pinterest_${pinId ?: ts}.mp4", it) }

        // ۴) نسخه موبایل صفحه — HTML موبایل گاهی ویدیویی دارد که نسخه دسکتاپ ندارد
        runCatching { fetchPage(link, MOBILE_UA) }.getOrNull()?.let { mobile ->
            htmlVideo(mobile.html)?.let { return video("pinterest_${pinId ?: ts}.mp4", it) }
            ogVideo(mobile.html)?.let { return video("pinterest_${pinId ?: ts}.mp4", it) }
        }

        // ۵) عکس — با بالاترین کیفیت موجود
        pinResourceImage?.let { return it }
        ogImage(html)?.let { return ResolvedMedia(it, "pinterest_${pinId ?: ts}.jpg", "image/jpeg") }

        throw SocialException("نتوانستم رسانه این پین را پیدا کنم — مطمئن شو لینک یک پین عمومی است")
    }

    /** فراخوانی API داخلی پینترست برای یک پین */
    private fun tryPinResource(pinId: String): ResolvedMedia? = try {
        val payload = JSONObject()
            .put(
                "options",
                JSONObject()
                    .put("id", pinId)
                    .put("field_set_key", "detailed")
                    .put("fetch_visual_search_objects", false)
            )
            .put("context", JSONObject())

        val request = Request.Builder()
            .url(
                "https://www.pinterest.com/resource/PinResource/get/" +
                    "?source_url=" + URLEncoder.encode("/pin/$pinId/", "UTF-8") +
                    "&data=" + URLEncoder.encode(payload.toString(), "UTF-8")
            )
            .header("User-Agent", MOBILE_UA)
            .header("Accept", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-Pinterest-Appstate", "active")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JSONObject(resp.body?.string() ?: return null)
            val data = root.optJSONObject("resource_response")?.optJSONObject("data") ?: return null

            // اول ویدیو — تک‌ویدیو یا پین‌های چندصفحه‌ای (Idea Pin)
            bestMp4(data.optJSONObject("videos")?.optJSONObject("video_list"))
                ?.let { return ResolvedMedia(it, "pinterest_$pinId.mp4", "video/mp4") }

            data.optJSONObject("story_pin_data")?.let { story ->
                val pages = story.optJSONArray("pages") ?: return@let
                for (i in 0 until pages.length()) {
                    val blocks = pages.optJSONObject(i)?.optJSONArray("blocks") ?: continue
                    for (j in 0 until blocks.length()) {
                        val video = blocks.optJSONObject(j)?.optJSONObject("video") ?: continue
                        bestMp4(video.optJSONObject("video_list"))?.let {
                            return ResolvedMedia(it, "pinterest_$pinId.mp4", "video/mp4")
                        }
                    }
                }
            }

            // عکس با کیفیت اصلی (orig)
            val orig = data.optJSONObject("images")?.optJSONObject("orig")?.optString("url")
            if (!orig.isNullOrBlank()) ResolvedMedia(orig, "pinterest_$pinId.jpg", "image/jpeg") else null
        }
    } catch (e: Exception) {
        null
    }

    /** از بین نسخه‌های ویدیو، بهترین فایل mp4 مستقیم را برمی‌گرداند (HLS رد می‌شود) */
    private fun bestMp4(videoList: JSONObject?): String? {
        if (videoList == null) return null
        var best: Pair<Int, String>? = null
        for (key in videoList.keys()) {
            val entry = videoList.optJSONObject(key) ?: continue
            val url = entry.optString("url")
            if (url.isBlank()) continue
            val low = url.lowercase()
            if (low.contains(".m3u8")) continue
            if (!low.contains(".mp4")) continue
            val score = when {
                key.uppercase().contains("1080") -> 110
                key.uppercase().contains("720") -> 100
                else -> 50
            } + entry.optInt("width", 0) / 10000
            if (best == null || score > best.first) best = score to url
        }
        return best?.second
    }

    /** جستجوی لینک‌های mp4 در سورس HTML صفحه پین */
    private fun htmlVideo(html: String): String? {
        // بعضی URLها به‌صورت https:\u002F\u002F... یا https:\/\/... کدگذاری می‌شوند
        val unescaped = html
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\/", "/")
        // v.pinimg.com و v1.pinimg.com هر دو میزبان ویدیوهای پینترست هستند
        val candidates = Regex("""https://v1?\.pinimg\.com/videos/[^"'<>\s\\]+\.mp4""")
            .findAll(unescaped)
            .map { it.value }
            .distinct()
            .toList()
        val pool = if (candidates.isNotEmpty()) candidates else {
            // آخرین شانس: هر mp4 دیگری داخل صفحه
            Regex("""https://[^"'<>\s\\]+\.mp4""").findAll(unescaped)
                .map { it.value }
                .distinct()
                .toList()
        }
        if (pool.isEmpty()) return null
        val best = pool.maxByOrNull { url ->
            val low = url.lowercase()
            when {
                low.contains("720p") -> 3
                low.contains("expmp4") -> 2
                else -> 1
            }
        }
        return best
    }

    private fun ogVideo(html: String): String? {
        listOf("og:video", "og:video:secure_url", "og:video:url").forEach { prop ->
            extractMeta(html, prop)?.let { url ->
                val low = url.lowercase()
                // فقط اگر واقعا ویدیو است — نه تصویر بندانگشتی
                if (low.contains("/videos/") || low.contains(".mp4")) {
                    return url.replace("&amp;", "&")
                }
            }
        }
        return null
    }

    private fun ogImage(html: String): String? {
        listOf("og:image", "og:image:secure_url", "twitter:image").forEach { prop ->
            extractMeta(html, prop)?.let { return it.replace("&amp;", "&") }
        }
        return null
    }

    private fun extractMeta(html: String, property: String): String? {
        val patterns = listOf(
            Regex("""<meta[^>]+property=["']$property["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]*property=["']$property["']""", RegexOption.IGNORE_CASE)
        )
        patterns.forEach { re -> re.find(html)?.let { return it.groupValues[1] } }
        return null
    }

    // ============================================================
    // یوتیوب و اینستاگرام — سرویس کوبالت با چند سرور پشتیبان
    // ============================================================

    private class Attempt(
        val media: ResolvedMedia? = null,
        val localProcessing: Boolean = false,
        val reason: String? = null
    )

    /** نتیجه مسابقه سرورها: اولین پاسخ موفق برنده است */
    private class RaceResult {
        val winner = AtomicReference<Attempt?>(null)
        val localProcessing = mutableListOf<String>()
        @Volatile var lastReason: String? = null
        fun recordLocal(ep: String) = synchronized(localProcessing) { localProcessing.add(ep) }
    }

    private suspend fun resolveViaCobalt(
        link: String,
        quality: String,
        audioOnly: Boolean,
        platform: SocialPlatform
    ): ResolvedMedia = withContext(Dispatchers.IO) {
        val endpoints = buildList {
            if (CUSTOM_ENDPOINT.isNotBlank()) add(CUSTOM_ENDPOINT)
            addAll(cobaltEndpoints)
        }.distinct()

        val q = if (audioOnly) "1080" else quality

        // مرحله ۱: همه سرورها همزمان با کیفیت اصلی صدا زده می‌شوند
        val race = RaceResult()
        supervisorScope {
            endpoints.map { ep ->
                launch {
                    val attempt = runCatching { callCobalt(ep, link, q, audioOnly, platform) }
                        .getOrElse { Attempt(reason = "ارتباط با سرور برقرار نشد") }
                    when {
                        attempt.media != null -> race.winner.compareAndSet(null, attempt)
                        attempt.localProcessing -> race.recordLocal(ep)
                        else -> if (attempt.reason != null) race.lastReason = attempt.reason
                    }
                }
            }
        }

        // مرحله ۲: اگر فقط سرورهای «پردازش محلی» جواب دادند، با 720 تک‌فایله امتحان کن
        var winner = race.winner.get()
        if (winner == null) {
            for (ep in race.localProcessing) {
                val attempt = runCatching { callCobalt(ep, link, "720", audioOnly, platform) }
                    .getOrElse { Attempt(reason = "ارتباط با سرور برقرار نشد") }
                if (attempt.media != null) {
                    winner = attempt
                    break
                }
            }
        }

        if (winner?.media != null) return@withContext winner.media

        val hint = if (race.lastReason?.contains("کلید دسترسی") == true) {
            " سرورهای عمومی محدود شده‌اند — می‌توانی طبق README یک سرور شخصی بسازی."
        } else ""
        throw SocialException(
            "دانلود از ${platform.label} ناموفق بود — ${race.lastReason ?: "هیچ سروری پاسخ نداد"}$hint"
        )
    }

    private fun callCobalt(
        endpoint: String,
        link: String,
        quality: String,
        audioOnly: Boolean,
        platform: SocialPlatform
    ): Attempt {
        val body = JSONObject().apply {
            put("url", link)
            put("videoQuality", quality) // max / 1080 / 720 / 480
            put("downloadMode", if (audioOnly) "audio" else "auto")
            put("filenameStyle", "pretty")
            put("youtubeVideoCodec", "h264") // حتماً mp4 تک‌فایله، نه جدا صدا و تصویر
        }.toString().toRequestBody("application/json".toMediaType())

        val builder = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("User-Agent", DownloadManager.USER_AGENT)
            .post(body)
        if (CUSTOM_API_KEY.isNotBlank() && endpoint == CUSTOM_ENDPOINT) {
            builder.header("Authorization", "Api-Key $CUSTOM_API_KEY")
        }

        return try {
            cobaltClient.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) return Attempt(reason = "پاسخ خالی از سرور")
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    return Attempt(reason = "پاسخ نامعتبر از سرور (کد ${resp.code})")
                }
                when (val status = json.optString("status")) {
                    "tunnel", "redirect", "stream" -> {
                        val url = json.getString("url")
                        val filename = json.optString("filename").ifBlank {
                            defaultName(platform, audioOnly)
                        }
                        val mime = json.optString("mime").ifBlank {
                            if (audioOnly) "audio/mp4" else "video/mp4"
                        }
                        Attempt(media = ResolvedMedia(url, filename, mime))
                    }
                    "local-processing" -> Attempt(localProcessing = true)
                    "error" -> Attempt(reason = errorFa(json))
                    else -> Attempt(reason = "وضعیت ناشناخته از سرور ($status)")
                }
            }
        } catch (e: Exception) {
            Attempt(reason = "ارتباط با سرور برقرار نشد")
        }
    }

    private fun errorFa(json: JSONObject): String {
        val errObj = json.optJSONObject("error")
        val code = errObj?.optString("code") ?: json.optString("error")
        return when (code) {
            "api.auth.key.missing",
            "api.auth.jwt.missing",
            "api.auth.jwt.invalid" -> "این سرور به کلید دسترسی نیاز دارد"
            "link.unsupported" -> "این لینک توسط سرویس استخراج پشتیبانی نمی‌شود"
            "link.invalid" -> "لینک واردشده معتبر نیست"
            "content.video.unavailable" -> "ویدیو در دسترس نیست (خصوصی یا حذف‌شده)"
            "content.video.age" -> "ویدیوهای با محدودیت سنی پشتیبانی نمی‌شوند"
            "content.post.private" -> "این پست خصوصی است و قابل دانلود نیست"
            "content.instagram.auth_required" -> "اینستاگرام برای این لینک احراز هویت می‌خواهد — لینک دیگری امتحان کن"
            "rate_exceeded" -> "تعداد درخواست‌ها زیاد است — چند لحظه بعد دوباره امتحان کن"
            else -> if (code.isBlank()) "خطای ناشناخته" else "خطا: $code"
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

    private data class PageContent(val html: String, val finalUrl: String)

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private fun fetchPage(url: String, ua: String = DESKTOP_UA): PageContent {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw SocialException("دسترسی به صفحه ممکن نشد (کد ${resp.code})")
            }
            return PageContent(
                html = resp.body?.string() ?: throw SocialException("محتوای صفحه خالی بود"),
                finalUrl = resp.request.url.toString()
            )
        }
    }
}
