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
 * - یوتیوب: سرویس کوبالت (چند سرور همزمان) + پشتیبان Piped
 * - اینستاگرام: کوبالت + زنجیره مستقیم API اینستاگرام برای پست/ریل
 *   و استوری/هایلایت + پشتیبان GraphQL بدون ورود
 * - اگر یک مسیر شکست بخورد، خودکار مسیر بعدی امتحان می‌شود
 */
object SocialResolver {

    // ------------------------------------------------------------
    // تنظیمات سرور شخصی (اختیاری)
    // ------------------------------------------------------------
    private const val CUSTOM_ENDPOINT = ""
    private const val CUSTOM_API_KEY = ""

    /**
     * سرورهای عمومی کوبالت — همزمان صدا زده می‌شوند؛
     * اولین پاسخ موفق برنده است. دو سرور اول تست‌شده و فعال هستند.
     */
    private val cobaltEndpoints = listOf(
        "https://dwnld.nichind.dev/",
        "https://co.otomir23.me/",
        "https://cobalt-api.kwiatekmiki.com/",
        "https://nyc1.coapi.ggtyler.dev/",
        "https://cobalt.255.one/"
    )

    /**
     * سرورهای پشتیبان Piped برای یوتیوب — وقتی همه سرورهای کوبالت
     * برای یک ویدیو جواب ندادند (محدودیت یوتیوب روی سرورها)، از این
     * مسیر مستقل استفاده می‌شود.
     */
    private val pipedEndpoints = listOf(
        "https://api.piped.private.coffee",
        "https://pipedapi.ducks.party",
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de"
    )

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"

    // اینستاگرام — شناسه اپلیکیشن وب و User-Agent اپ رسمی
    private const val IG_APP_ID = "936619743392459"
    private const val IG_APP_UA =
        "Instagram 195.0.0.31.123 Android (26/8.0.0; 480dpi; 1080x1920; OnePlus; OnePlus5T; op8t19; en_IN; 302733750)"
    private const val IG_GRAPHQL_DOC = "8845758582119845"

    /** کلاینت عمومی برای دریافت صفحات (پینترست) */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** کلاینت سریع برای سرورهای کوبالت/پایپد — سرور مرده نباید معطل کند */
    private val cobaltClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /** کلاینت API اینستاگرام — زمان کمی بیشتر برای پاسخ‌های موبایل */
    private val igClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(
        link: String,
        quality: String,
        audioOnly: Boolean,
        platform: SocialPlatform
    ): ResolvedMedia = withContext(Dispatchers.IO) {
        when (platform) {
            SocialPlatform.PINTEREST -> resolvePinterest(link)

            SocialPlatform.YOUTUBE -> {
                try {
                    resolveViaCobalt(link, quality, audioOnly, platform)
                } catch (e: SocialException) {
                    // پشتیبان Piped فقط برای ویدیو (صدا ندارد)
                    if (audioOnly) throw e
                    resolveViaPiped(link) ?: throw e
                }
            }

            SocialPlatform.INSTAGRAM -> {
                if (link.contains("/stories/", ignoreCase = true)) {
                    resolveInstagramStory(link)
                } else {
                    try {
                        resolveViaCobalt(link, quality, audioOnly, platform)
                    } catch (e: SocialException) {
                        if (audioOnly) throw e
                        resolveInstagramGraph(link) ?: throw e
                    }
                }
            }
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

    // ============================================================
    // یوتیوب — پشتیبان Piped (مستقل از کوبالت)
    // ============================================================

    /** استخراج شناسه ۱۱ رقمی ویدیو از انواع لینک یوتیوب */
    private fun extractVideoId(link: String): String? =
        Regex("(?:v=|/shorts/|/embed/|/live/|youtu\\.be/|/v/)([A-Za-z0-9_-]{11})")
            .find(link)?.groupValues?.get(1)

    /**
     * مسیر پشتیبان یوتیوب از طریق سرورهای Piped.
     * این سرورها مستقیماً با یوتیوب کار می‌کنند و وقتی کوبالت‌ها
     * به‌خاطر محدودیت یوتیوب جواب ندهند، همین مسیر جواب می‌دهد.
     * (حداکثر کیفیت تک‌فایله؛ برای «فقط صدا» مناسب نیست)
     */
    private suspend fun resolveViaPiped(link: String): ResolvedMedia? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(link) ?: return@withContext null
        val result = AtomicReference<ResolvedMedia?>(null)

        supervisorScope {
            pipedEndpoints.map { base ->
                launch {
                    runCatching {
                        val req = Request.Builder()
                            .url("$base/streams/$videoId")
                            .header("Accept", "application/json")
                            .header("User-Agent", MOBILE_UA)
                            .build()

                        cobaltClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use
                            val json = try {
                                JSONObject(resp.body?.string() ?: return@use)
                            } catch (e: Exception) {
                                return@use
                            }
                            if (json.has("error")) return@use
                            val title = json.optString("title").ifBlank { "youtube_$videoId" }
                            val streams = json.optJSONArray("videoStreams") ?: return@use

                            var bestRank = 0
                            var bestUrl: String? = null
                            for (i in 0 until streams.length()) {
                                val s = streams.optJSONObject(i) ?: continue
                                if (s.optBoolean("videoOnly")) continue // فقط تک‌فایله
                                val url = s.optString("url")
                                if (url.isBlank() || url.contains(".m3u8")) continue // HLS رد
                                val qLabel = s.optString("quality")
                                val height = qLabel.filter { it.isDigit() }.toIntOrNull()
                                    ?: if (qLabel.equals("LBRY", true) &&
                                        s.optString("format").contains("MP4", true)) 720 else 0
                                if (height == 0) continue
                                val rank = height
                                if (rank > bestRank) {
                                    bestRank = rank
                                    bestUrl = url
                                }
                            }

                            bestUrl?.let {
                                val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().take(80)
                                result.compareAndSet(
                                    null,
                                    ResolvedMedia(it, "$safe.mp4", "video/mp4")
                                )
                            }
                        }
                    }
                }
            }
        }

        result.get()
    }

    // ============================================================
    // اینستاگرام — استوری و هایلایت از مسیر مستقیم API
    // ============================================================

    private data class IgStoryTarget(
        val username: String?,
        val mediaId: String?,
        val highlightId: String?
    )

    private fun parseInstagramStory(link: String): IgStoryTarget {
        val hl = Regex("/stories/highlights/(\\d+)", RegexOption.IGNORE_CASE)
            .find(link)?.groupValues?.get(1)
        val m = Regex("/stories/([A-Za-z0-9_.]+)/?(\\d+)?", RegexOption.IGNORE_CASE)
            .find(link)
        val username = m?.groupValues?.get(1)?.takeUnless { it.equals("highlights", true) }
        val mediaId = m?.groupValues?.get(2)
        return IgStoryTarget(username, mediaId, hl)
    }

    /**
     * زنجیره دانلود استوری/هایلایت اینستاگرام:
     * ۱) اگر شناسه رسانه در لینک باشد → media/info (دقیق‌ترین)
     * ۲) هایلایت → reels_media
     * ۳) نام کاربری → وب‌پروفایل برای شناسه عددی → فید استوری
     */
    private suspend fun resolveInstagramStory(link: String): ResolvedMedia = withContext(Dispatchers.IO) {
        val t = parseInstagramStory(link)
        val ts = System.currentTimeMillis()

        if (t.mediaId != null) {
            igMediaInfo(t.mediaId)?.let { return@withContext it }
        }
        if (t.highlightId != null) {
            igHighlight(t.highlightId)?.let { return@withContext it }
        }
        if (t.username != null) {
            val uid = igUserId(t.username)
            if (uid != null) {
                igStoryTray(uid, t.username)?.let { return@withContext it }
            }
        }

        throw SocialException(
            "دانلود استوری اینستاگرام ممکن نشد — اینستاگرام دسترسی استوری‌ها را برای ابزارهای خارجی " +
                "به‌شدت محدود کرده است. اگر حساب خصوصی است یا استوری منقضی شده، قابل دانلود نیست. " +
                "برای ویدیوهای معمولی از لینک پست یا ریل استفاده کن."
        )
    }

    /** اطلاعات یک رسانه با شناسه عددی (کار می‌کند برای لینک استوری دارای شناسه) */
    private fun igMediaInfo(mediaId: String): ResolvedMedia? = runCatching {
        val req = igRequest("https://i.instagram.com/api/v1/media/$mediaId/info/")
        igClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string() ?: return null)
            val item = json.optJSONArray("items")?.optJSONObject(0) ?: return null
            val user = item.optJSONObject("user")?.optString("username").orEmpty()
            igPickMedia(item, "instagram_story_${user.ifBlank { mediaId }}.mp4")
        }
    }.getOrNull()

    /** آیتم‌های یک هایلایت */
    private fun igHighlight(highlightId: String): ResolvedMedia? = runCatching {
        val req = igRequest("https://i.instagram.com/api/v1/feed/reels_media/?media_ids=$highlightId")
        igClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string() ?: return null)
            val reels = json.optJSONObject("reels") ?: return null
            val keys = reels.keys()
            if (!keys.hasNext()) return null
            val reel = reels.optJSONObject(keys.next()) ?: return null
            val items = reel.optJSONArray("items") ?: return null
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                igPickMedia(item, "instagram_highlight_$highlightId.mp4")?.let { return it }
            }
            null
        }
    }.getOrNull()

    /** شناسه عددی کاربر از روی نام کاربری (وب‌پروفایل) */
    private fun igUserId(username: String): String? {
        // اول با UA موبایل اپ، بعد با UA مرورگر — بعضی IPها فقط یکی را می‌پذیرند
        val urls = listOf(
            "https://i.instagram.com/api/v1/users/web_profile_info/?username=$username",
            "https://www.instagram.com/api/v1/users/web_profile_info/?username=$username"
        )
        for ((idx, u) in urls.withIndex()) {
            runCatching {
                val req = Request.Builder()
                    .url(u)
                    .header("User-Agent", if (idx == 0) IG_APP_UA else MOBILE_UA)
                    .header("X-IG-App-ID", IG_APP_ID)
                    .header("Accept", "application/json")
                    .build()
                igClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val json = JSONObject(resp.body?.string() ?: return null)
                    val id = json.optJSONObject("data")?.optJSONObject("user")?.optString("id")
                    if (!id.isNullOrBlank()) return id
                    null
                }
            }
        }
        return null
    }

    /** فید استوری فعلی یک کاربر — اولین ویدیوی موجود برمی‌گردد */
    private fun igStoryTray(userId: String, username: String): ResolvedMedia? = runCatching {
        val req = igRequest("https://i.instagram.com/api/v1/feed/user/$userId/story/")
        igClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string() ?: return null)
            val tray = json.optJSONArray("tray") ?: json.optJSONArray("items") ?: return null
            for (i in 0 until tray.length()) {
                val entry = tray.optJSONObject(i) ?: continue
                val items = entry.optJSONArray("items") ?: continue
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    igPickMedia(item, "instagram_story_$username.mp4")?.let { return it }
                }
            }
            null
        }
    }.getOrNull()

    /**
     * از یک آیتم اینستاگرام بهترین ویدیو (و در نبودش، عکس) را برمی‌گرداند.
     * video_versions بر اساس کیفیت مرتب نیستند — بهترین را بر اساس عرض انتخاب می‌کنیم.
     */
    private fun igPickMedia(item: JSONObject, fileName: String): ResolvedMedia? {
        val videos = item.optJSONArray("video_versions")
        if (videos != null && videos.length() > 0) {
            var bestW = -1
            var bestUrl: String? = null
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val url = v.optString("url")
                if (url.isBlank()) continue
                val w = v.optInt("width", 0)
                if (w > bestW) {
                    bestW = w
                    bestUrl = url
                }
            }
            bestUrl?.let { return ResolvedMedia(it, fileName, "video/mp4") }
        }
        // عکس استوری
        val candidates = item.optJSONObject("image_versions2")
            ?.optJSONArray("candidates") ?: return null
        var bestW = -1
        var bestUrl: String? = null
        for (i in 0 until candidates.length()) {
            val c = candidates.optJSONObject(i) ?: continue
            val url = c.optString("url")
            if (url.isBlank()) continue
            val w = c.optInt("width", 0)
            if (w > bestW) {
                bestW = w
                bestUrl = url
            }
        }
        return bestUrl?.let {
            ResolvedMedia(it, fileName.replace(".mp4", ".jpg"), "image/jpeg")
        }
    }

    /** درخواست استاندارد API موبایل اینستاگرام */
    private fun igRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", IG_APP_UA)
        .header("X-IG-App-ID", IG_APP_ID)
        .header("Accept", "application/json")
        .build()

    // ============================================================
    // اینستاگرام — پشتیبان GraphQL بدون ورود (پست و ریل)
    // ============================================================

    /**
     * وقتی کوبالت‌ها شکست خوردند، مستقیم از GraphQL وب اینستاگرام
     * رسانه را بیرون می‌کشیم. از IPهای خانگی/موبایل معمولاً پاسخ می‌دهد.
     */
    private suspend fun resolveInstagramGraph(link: String): ResolvedMedia? = withContext(Dispatchers.IO) {
        val shortcode = Regex("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .find(link)?.groupValues?.get(1) ?: return@withContext null

        runCatching {
            val form = "variables=" + URLEncoder.encode(
                JSONObject().put("shortcode", shortcode).toString(), "UTF-8"
            ) + "&doc_id=" + IG_GRAPHQL_DOC

            val request = Request.Builder()
                .url("https://www.instagram.com/graphql/query")
                .header("User-Agent", MOBILE_UA)
                .header("X-IG-App-ID", IG_APP_ID)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "*/*")
                .header("Referer", "https://www.instagram.com/")
                .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            igClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string() ?: return@use null)
                val media = json.optJSONObject("data")
                    ?.optJSONObject("xdt_shortcode_media") ?: return@use null

                if (media.optBoolean("is_video")) {
                    // GraphQL ممکن است video_url مستقیم بدهد یا آرایه video_versions
                    val url = media.optString("video_url").takeIf { it.isNotBlank() }
                        ?: pickBestIgVersion(media.optJSONArray("video_versions"))
                    if (url != null) {
                        return@use ResolvedMedia(url, "instagram_$shortcode.mp4", "video/mp4")
                    }
                }
                // پست چندتایی (sidecar) — اولین ویدیو یا عکس داخل فرزندان
                val edges = media.optJSONObject("edge_sidecar_to_children")
                    ?.optJSONArray("edges")
                if (edges != null) {
                    for (i in 0 until edges.length()) {
                        val child = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                        if (child.optBoolean("is_video")) {
                            val url = child.optString("video_url").takeIf { it.isNotBlank() }
                                ?: pickBestIgVersion(child.optJSONArray("video_versions"))
                            if (url != null) {
                                return@use ResolvedMedia(url, "instagram_$shortcode.mp4", "video/mp4")
                            }
                        }
                    }
                }
                // عکس پست
                val display = media.optString("display_url").takeIf { it.isNotBlank() }
                    ?: media.optString("thumbnail_src").takeIf { it.isNotBlank() }
                display?.let { ResolvedMedia(it, "instagram_$shortcode.jpg", "image/jpeg") }
            }
        }.getOrNull()
    }

    /** بهترین نسخه از آرایه video_versions (بیشترین عرض) */
    private fun pickBestIgVersion(versions: JSONArray?): String? {
        if (versions == null) return null
        var bestW = -1
        var bestUrl: String? = null
        for (i in 0 until versions.length()) {
            val v = versions.optJSONObject(i) ?: continue
            val url = v.optString("url")
            if (url.isBlank()) continue
            val w = v.optInt("width", 0)
            if (w > bestW) {
                bestW = w
                bestUrl = url
            }
        }
        return bestUrl
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
