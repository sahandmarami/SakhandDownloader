package com.sakhand.downloadmanager.social

import com.sakhand.downloadmanager.data.SocialPlatform
import com.sakhand.downloadmanager.engine.DownloadManager
import com.sakhand.downloadmanager.engine.LenientTls
import com.sakhand.downloadmanager.engine.SmartDns
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
import java.math.BigInteger
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** خطای قابل نمایش به کاربر */
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
 * - یوتیوب: کوبالت (چند سرور همزمان) → پشتیبان Piped → پشتیبان Invidious
 * - اینستاگرام: کوبالت → مسیر مستقیم API اینستاگرام برای پست/ریل/استوری/هایلایت
 * - لیست سرورها به‌صورت «ریموت» به‌روزرسانی می‌شود؛ اگر سروری از کار بیفتد،
 *   بدون نیاز به نصب نسخهٔ جدید قابل تعمیر است.
 * - اگر یک مسیر شکست بخورد، خودکار مسیر بعدی امتحان می‌شود
 */
object SocialResolver {

    // ------------------------------------------------------------
    // تنظیمات سرور شخصی (اختیاری)
    // ------------------------------------------------------------
    private const val CUSTOM_ENDPOINT = ""
    private const val CUSTOM_API_KEY = ""

    // ------------------------------------------------------------
    // تنظیمات ریموت — لیست سرورهای فعال از این آدرس خوانده می‌شود
    // ------------------------------------------------------------
    private const val REMOTE_CONFIG_URL =
        "https://gist.githubusercontent.com/sahandmarami/63da5bd589edcb8a817bb2416b2de7aa/raw/endpoints.json"
    private const val REMOTE_TTL_MS = 10 * 60 * 1000L

    @Volatile private var remoteCobalt: List<String>? = null
    @Volatile private var remotePiped: List<String>? = null
    @Volatile private var remoteInvidious: List<String>? = null
    @Volatile private var remoteAt = 0L

    /** لیست داخلی — وقتی تنظیمات ریموت در دسترس نباشد استفاده می‌شود */
    private val bundledCobalt = listOf(
        "https://api.cobalt.liubquanti.click/",
        "https://co.otomir23.me/",
        "https://dwnld.nichind.dev/",
        "https://nyc1.coapi.ggtyler.dev/"
    )

    private val bundledPiped = listOf(
        "https://api.piped.private.coffee",
        "https://pipedapi.ducks.party"
    )

    private val bundledInvidious = listOf(
        "https://inv.nadeko.net",
        "https://yewtu.be",
        "https://invidious.f5.si"
    )

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"

    // اینستاگرام — شناسه اپلیکیشن وب و User-Agent اپ رسمی
    private const val IG_APP_ID = "936619743392459"
    private const val IG_APP_UA =
        "Instagram 195.0.0.31.123 Android (26/8.0.0; 480dpi; 1080x1920; OnePlus; OnePlus5T; op8t19; en_IN; 302733750)"
    private val IG_GRAPHQL_DOCS = listOf("8845758582119845", "10015901848480474")

    /** پیام استاندارد شکست سطح اتصال — مبنای تشخیص نیاز به پاس نجات */
    private const val CONNECTION_REASON = "ارتباط با سرور برقرار نشد"

    /** راهنمای فیلترشکن برای پیام‌های خطای نهایی */
    private const val HINT_VPN =
        " اگر یوتیوب یا اینستاگرام را با فیلترشکن باز می‌کنی، فیلترشکن را روشن نگه دار و دوباره تلاش کن. " +
        "اگر باز هم نشد، ساعت و تاریخ دستگاه را روی تنظیم خودکار بگذار و دوباره امتحان کن."

    /** کلاینت عمومی برای دریافت صفحات (پینترست) */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .dns(SmartDns)
        .build()

    /** کلاینت سریع برای سرورهای کوبالت/پایپد — شبکه‌های موبایل آهسته‌اند */
    private val cobaltClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .dns(SmartDns)
        .build()

    /** کلاینت نجات — مثل cobaltClient ولی خطای گواهی TLS را نادیده می‌گیرد؛
     *  فقط وقتی همه سرورها در پاس اول شکست خوردند به‌کار می‌رود */
    private val cobaltLenientClient: OkHttpClient by lazy {
        LenientTls.apply(
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .dns(SmartDns)
                .build()
        )
    }
    /** کلاینت API اینستاگرام — زمان کمی بیشتر برای پاسخ‌های موبایل */
    private val igClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .dns(SmartDns)
        .build()

    /** کلاینت Invidious — کوتاه چون چند سرور پشت‌سرهم امتحان می‌شوند */
    private val invClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .dns(SmartDns)
        .build()

    /** کلاینت دریافت تنظیمات ریموت */
    private val configClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .dns(SmartDns)
        .build()

    // ------------------------------------------------------------
    // لیست سرورها: ریموت + داخلی
    // ------------------------------------------------------------

    private fun refreshRemoteLists() {
        val now = System.currentTimeMillis()
        if (remoteAt != 0L && now - remoteAt < REMOTE_TTL_MS) return
        synchronized(this) {
            if (remoteAt != 0L && System.currentTimeMillis() - remoteAt < REMOTE_TTL_MS) return
            try {
                val req = Request.Builder()
                    .url(REMOTE_CONFIG_URL)
                    .header("User-Agent", MOBILE_UA)
                    .header("Accept", "application/json")
                    .build()
                configClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string() ?: "")
                        fun parse(key: String, withSlash: Boolean): List<String>? {
                            val arr = json.optJSONArray(key) ?: return null
                            val out = mutableListOf<String>()
                            for (i in 0 until arr.length()) {
                                val s = arr.optString(i).trim()
                                if (s.isBlank()) continue
                                out.add(if (withSlash && !s.endsWith("/")) "$s/" else s)
                            }
                            return if (out.isEmpty()) null else out
                        }
                        parse("cobalt", true)?.let { remoteCobalt = it }
                        parse("piped", false)?.let { remotePiped = it }
                        parse("invidious", false)?.let { remoteInvidious = it }
                        remoteAt = System.currentTimeMillis()
                    }
                }
            } catch (_: Exception) {
                // تنظیمات ریموت در دسترس نیست — لیست داخلی به‌کار می‌رود
            }
        }
    }

    private fun cobaltList(): List<String> {
        refreshRemoteLists()
        return buildList {
            if (CUSTOM_ENDPOINT.isNotBlank()) add(CUSTOM_ENDPOINT)
            addAll(remoteCobalt.orEmpty())
            addAll(bundledCobalt)
        }.distinct()
    }

    private fun pipedList(): List<String> {
        refreshRemoteLists()
        return (remotePiped.orEmpty() + bundledPiped).distinct()
    }

    private fun invidiousList(): List<String> {
        refreshRemoteLists()
        return (remoteInvidious.orEmpty() + bundledInvidious).distinct()
    }

    // ------------------------------------------------------------
    // نقطهٔ ورود
    // ------------------------------------------------------------

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
                    return@withContext resolveViaCobalt(link, quality, audioOnly, platform)
                } catch (e: SocialException) {
                    // پشتیبان Piped و Invidious فقط برای ویدیو (صدا ندارند)
                    if (audioOnly) throw e
                    resolveViaPiped(link)?.let { return@withContext it }
                    resolveViaInvidious(link)?.let { return@withContext it }
                    throw SocialException((e.message ?: "دانلود ناموفق بود") + HINT_VPN)
                }
            }

            SocialPlatform.INSTAGRAM -> {
                if (link.contains("/stories/", ignoreCase = true)) {
                    resolveInstagramStory(link)
                } else {
                    try {
                        return@withContext resolveViaCobalt(link, quality, audioOnly, platform)
                    } catch (e: SocialException) {
                        if (audioOnly) throw e
                        resolveInstagramPost(link)?.let { return@withContext it }
                        throw SocialException((e.message ?: "دانلود ناموفق بود") + HINT_VPN)
                    }
                }
            }
        }
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
        private val reasons = mutableListOf<String>()
        @Volatile var lastReason: String? = null
            private set
        fun recordReason(reason: String) = synchronized(reasons) {
            reasons.add(reason)
            lastReason = reason
        }
        /** فقط شکست سطح اتصال؟ (یعنی DNS یا گواهی TLS مشکل دارد، نه خود سرور) */
        val allConnectionFailures: Boolean
            get() = synchronized(reasons) { reasons.isNotEmpty() && reasons.all { it == CONNECTION_REASON } }
        fun recordLocal(ep: String) = synchronized(localProcessing) { localProcessing.add(ep) }
    }

    private suspend fun resolveViaCobalt(
        link: String,
        quality: String,
        audioOnly: Boolean,
        platform: SocialPlatform
    ): ResolvedMedia = withContext(Dispatchers.IO) {
        val endpoints = cobaltList()
        val q = if (audioOnly) "1080" else quality

        suspend fun runRace(lenient: Boolean): RaceResult {
            val race = RaceResult()
            val httpClient = if (lenient) cobaltLenientClient else cobaltClient
            supervisorScope {
                endpoints.map { ep ->
                    launch {
                        val attempt = runCatching { callCobalt(httpClient, ep, link, q, audioOnly, platform) }
                            .getOrElse { Attempt(reason = CONNECTION_REASON) }
                        when {
                            attempt.media != null -> race.winner.compareAndSet(null, attempt)
                            attempt.localProcessing -> race.recordLocal(ep)
                            else -> if (attempt.reason != null) race.recordReason(attempt.reason!!)
                        }
                    }
                }
            }
            return race
        }

        // مرحله ۱: همه سرورها همزمان با کیفیت اصلی صدا زده می‌شوند
        val race = runRace(lenient = false)

        // مرحله ۲: اگر فقط سرورهای «پردازش محلی» جواب دادند، با 720 تک‌فایله امتحان کن
        var winner = race.winner.get()
        if (winner == null) {
            for (ep in race.localProcessing) {
                val attempt = runCatching { callCobalt(cobaltClient, ep, link, "720", audioOnly, platform) }
                    .getOrElse { Attempt(reason = CONNECTION_REASON) }
                if (attempt.media != null) {
                    winner = attempt
                    break
                }
            }
        }

        // مرحله ۳: اگر همه در سطح اتصال شکست خوردند (DNS خراب یا گواهی TLS)،
        // یک پاس دیگر با کلاینت تساهل‌گر امتحان می‌شود
        if (winner == null && race.allConnectionFailures && endpoints.isNotEmpty()) {
            val rescue = runRace(lenient = true)
            winner = rescue.winner.get()
        }

        if (winner?.media != null) return@withContext winner.media

        val hint = if (race.lastReason?.contains("کلید دسترسی") == true) {
            " سرورهای عمومی محدود شده‌اند — می‌توانی طبق README یک سرور شخصی بسازی."
        } else ""
        throw SocialException(
            "دانلود از ${platform.label} ناموفق بود — ${race.lastReason ?: "هیچ سروری پاسخ نداد"} " +
                "(${endpoints.size} سرور امتحان شد)$hint"
        )
    }

    private fun callCobalt(
        httpClient: OkHttpClient,
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
            httpClient.newCall(builder.build()).execute().use { resp ->
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
                    "picker" -> {
                        // پست چندرسانه‌ای — اولین ویدیو، وگرنه اولین عکس
                        val items = json.optJSONArray("picker")
                        var picked: JSONObject? = null
                        if (items != null) {
                            for (i in 0 until items.length()) {
                                val entry = items.optJSONObject(i) ?: continue
                                val eUrl = entry.optString("url")
                                if (eUrl.isBlank()) continue
                                if (entry.optString("type").equals("video", true)) {
                                    picked = entry
                                    break
                                }
                                if (picked == null) picked = entry
                            }
                        }
                        val pUrl = picked?.optString("url")
                        if (pUrl.isNullOrBlank()) {
                            Attempt(reason = "هیچ رسانه‌ای در پست پیدا نشد")
                        } else {
                            val isVideo = picked.optString("type").equals("video", true)
                            val name = json.optString("filename").ifBlank {
                                val ts = System.currentTimeMillis()
                                if (isVideo) "instagram_$ts.mp4" else "instagram_$ts.jpg"
                            }
                            Attempt(
                                media = ResolvedMedia(
                                    pUrl,
                                    name,
                                    if (isVideo) "video/mp4" else "image/jpeg"
                                )
                            )
                        }
                    }
                    "local-processing" -> Attempt(localProcessing = true)
                    "error" -> Attempt(reason = errorFa(json))
                    else -> Attempt(reason = "وضعیت ناشناخته از سرور ($status)")
                }
            }
        } catch (e: Exception) {
            Attempt(reason = CONNECTION_REASON)
        }
    }

    private fun errorFa(json: JSONObject): String {
        val errObj = json.optJSONObject("error")
        val code = errObj?.optString("code") ?: json.optString("error")
        return when {
            code.endsWith("auth.key.missing") || code.endsWith("auth.jwt.missing") ||
                code.endsWith("auth.jwt.invalid") -> "این سرور به کلید دسترسی نیاز دارد"
            code == "link.unsupported" -> "این لینک توسط سرویس استخراج پشتیبانی نمی‌شود"
            code == "link.invalid" -> "لینک واردشده معتبر نیست"
            code == "content.video.unavailable" -> "ویدیو در دسترس نیست (خصوصی یا حذف‌شده)"
            code == "content.video.age" -> "ویدیوهای با محدودیت سنی پشتیبانی نمی‌شوند"
            code == "content.post.private" -> "این پست خصوصی است و قابل دانلود نیست"
            code == "content.instagram.auth_required" -> "اینستاگرام برای این لینک احراز هویت می‌خواهد — لینک دیگری امتحان کن"
            code == "rate_exceeded" -> "تعداد درخواست‌ها زیاد است — چند لحظه بعد دوباره امتحان کن"
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
     * لینک‌های این سرورها از پروکسی خود سرور عبور می‌کنند و
     * حتی وقتی دسترسی مستقیم به یوتیوب ممکن نباشد کار می‌کنند.
     */
    private suspend fun resolveViaPiped(link: String): ResolvedMedia? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(link) ?: return@withContext null
        val result = AtomicReference<ResolvedMedia?>(null)

        supervisorScope {
            pipedList().map { base ->
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
                                val fmt = s.optString("format")
                                if (fmt.contains("HLS", true)) continue // پخش زنده/چندقطعی رد
                                val url = s.optString("url")
                                if (url.isBlank() || url.contains(".m3u8")) continue
                                val qLabel = s.optString("quality")
                                val height = qLabel.filter { it.isDigit() }.toIntOrNull()
                                    ?: if (qLabel.equals("LBRY", true) &&
                                        fmt.contains("MP4", true)) 720 else 0
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
    // یوتیوب — پشتیبان Invidious (آخرین لایه)
    // ============================================================

    /**
     * مسیر پشتیبان سوم: Invidious با itagهای تک‌فایله (22 و 18).
     * با local=true فایل از پروکسی خود سرور عبور می‌کند.
     */
    private suspend fun resolveViaInvidious(link: String): ResolvedMedia? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(link) ?: return@withContext null
        val deadline = System.currentTimeMillis() + 45_000L
        var result: ResolvedMedia? = null

        loop@ for (itag in intArrayOf(22, 18)) {
            for (base in invidiousList()) {
                if (result != null) break@loop
                if (System.currentTimeMillis() > deadline) break@loop
                runCatching {
                    val req = Request.Builder()
                        .url("$base/latest_version?id=$videoId&itag=$itag&local=true")
                        .header("User-Agent", MOBILE_UA)
                        .build()
                    invClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val ct = resp.header("Content-Type").orEmpty()
                        if (!ct.contains("video")) return@use
                        val url = resp.request.url.toString()
                        if (url.isBlank()) return@use
                        result = ResolvedMedia(url, "youtube_$videoId.mp4", "video/mp4")
                    }
                }
            }
        }
        result
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

        if (t.mediaId != null) {
            igMediaInfo(t.mediaId, "instagram_story_${t.mediaId}.mp4")?.let { return@withContext it }
            igMediaInfoWeb(t.mediaId, "instagram_story_${t.mediaId}.mp4")?.let { return@withContext it }
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
                "برای ویدیوهای معمولی از لینک پست یا ریل استفاده کن.$HINT_VPN"
        )
    }

    // ============================================================
    // اینستاگرام — پست و ریل از مسیر مستقیم API
    // ============================================================

    /**
     * وقتی کوبالت‌ها جواب ندادند، مستقیم از API اینستاگرام رسانه را
     * بیرون می‌کشیم. از IP خانگی/موبایل (به‌خصوص با فیلترشکن) معمولاً پاسخ می‌دهد.
     */
    private suspend fun resolveInstagramPost(link: String): ResolvedMedia? = withContext(Dispatchers.IO) {
        val shortcode = Regex("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .find(link)?.groupValues?.get(1) ?: return@withContext null

        val name = "instagram_$shortcode.mp4"

        // ۱) media/info با شناسهٔ رمزگشایی‌شده از shortcode (بدون نیاز به شبکه)
        val mediaId = shortcodeToMediaId(shortcode)
        if (mediaId != null) {
            igMediaInfo(mediaId, name)?.let { return@withContext it }
            igMediaInfoWeb(mediaId, name)?.let { return@withContext it }
        }

        // ۲) GraphQL وب اینستاگرام
        resolveInstagramGraph(shortcode)?.let { return@withContext it }

        null
    }

    /**
     * تبدیل shortcode به شناسهٔ عددی رسانه — محاسبهٔ کاملاً محلی.
     * هر نویسه در پایه ۶۴ با الفبای مخصوص اینستاگرام رمزگشایی می‌شود.
     */
    private fun shortcodeToMediaId(shortcode: String): String? {
        if (shortcode.length < 5) return null
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var id = BigInteger.ZERO
        val base = BigInteger.valueOf(64)
        for (c in shortcode) {
            val idx = alphabet.indexOf(c)
            if (idx < 0) return null
            id = id.multiply(base).add(BigInteger.valueOf(idx.toLong()))
        }
        return id.toString()
    }

    /** اطلاعات یک رسانه با شناسهٔ عددی — هاست اپ موبایل */
    private fun igMediaInfo(mediaId: String, fileName: String): ResolvedMedia? = try {
        val req = igRequest("https://i.instagram.com/api/v1/media/$mediaId/info/")
        igClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string() ?: return null)
            val item = json.optJSONArray("items")?.optJSONObject(0) ?: return null
            igPickMedia(item, fileName)
        }
    } catch (e: Exception) {
        null
    }

    /** اطلاعات یک رسانه با شناسهٔ عددی — هاست وب (بعضی IPها فقط این را می‌پذیرند) */
    private fun igMediaInfoWeb(mediaId: String, fileName: String): ResolvedMedia? = try {
        val req = Request.Builder()
            .url("https://www.instagram.com/api/v1/media/$mediaId/info/")
            .header("User-Agent", MOBILE_UA)
            .header("X-IG-App-ID", IG_APP_ID)
            .header("Accept", "application/json")
            .build()
        igClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string() ?: return null)
            val item = json.optJSONArray("items")?.optJSONObject(0) ?: return null
            igPickMedia(item, fileName)
        }
    } catch (e: Exception) {
        null
    }

    /** آیتم‌های یک هایلایت — دو هاست امتحان می‌شود */
    private fun igHighlight(highlightId: String): ResolvedMedia? {
        igHighlightHost("https://i.instagram.com", highlightId)?.let { return it }
        return igHighlightHost("https://www.instagram.com", highlightId)
    }

    private fun igHighlightHost(host: String, highlightId: String): ResolvedMedia? = try {
        val req = Request.Builder()
            .url("$host/api/v1/feed/reels_media/?media_ids=$highlightId")
            .header("User-Agent", IG_APP_UA)
            .header("X-IG-App-ID", IG_APP_ID)
            .header("Accept", "application/json")
            .build()
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
    } catch (e: Exception) {
        null
    }

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
    private fun igStoryTray(userId: String, username: String): ResolvedMedia? {
        igStoryTrayHost("https://i.instagram.com", userId, username)?.let { return it }
        return igStoryTrayHost("https://www.instagram.com", userId, username)
    }

    private fun igStoryTrayHost(host: String, userId: String, username: String): ResolvedMedia? = try {
        val req = Request.Builder()
            .url("$host/api/v1/feed/user/$userId/story/")
            .header("User-Agent", IG_APP_UA)
            .header("X-IG-App-ID", IG_APP_ID)
            .header("Accept", "application/json")
            .build()
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
    } catch (e: Exception) {
        null
    }

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

    private suspend fun resolveInstagramGraph(shortcode: String): ResolvedMedia? = withContext(Dispatchers.IO) {
        for (docId in IG_GRAPHQL_DOCS) {
            val media = runCatching {
                val form = "variables=" + URLEncoder.encode(
                    JSONObject().put("shortcode", shortcode).toString(), "UTF-8"
                ) + "&doc_id=" + docId

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
                    json.optJSONObject("data")?.optJSONObject("xdt_shortcode_media")
                }
            }.getOrNull()

            if (media != null) {
                igMediaFromGraph(media, shortcode)?.let { return@withContext it }
            }
        }
        null
    }

    /** استخراج ویدیو/عکس از پاسخ GraphQL */
    private fun igMediaFromGraph(media: JSONObject, shortcode: String): ResolvedMedia? {
        if (media.optBoolean("is_video")) {
            // GraphQL ممکن است video_url مستقیم بدهد یا آرایه video_versions
            val url = media.optString("video_url").takeIf { it.isNotBlank() }
                ?: pickBestIgVersion(media.optJSONArray("video_versions"))
            if (url != null) {
                return ResolvedMedia(url, "instagram_$shortcode.mp4", "video/mp4")
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
                        return ResolvedMedia(url, "instagram_$shortcode.mp4", "video/mp4")
                    }
                }
            }
        }
        // عکس پست
        val display = media.optString("display_url").takeIf { it.isNotBlank() }
            ?: media.optString("thumbnail_src").takeIf { it.isNotBlank() }
        return display?.let { ResolvedMedia(it, "instagram_$shortcode.jpg", "image/jpeg") }
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
