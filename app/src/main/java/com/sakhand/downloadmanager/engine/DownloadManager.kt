package com.sakhand.downloadmanager.engine

import android.content.Context
import android.webkit.MimeTypeMap
import com.sakhand.downloadmanager.data.ChunkState
import com.sakhand.downloadmanager.data.DownloadItem
import com.sakhand.downloadmanager.data.DownloadStatus
import com.sakhand.downloadmanager.data.SocialPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * موتور دانلود چندتردی سخند دانلود منیجر
 *
 * - فایل را به ۸ بخش تقسیم و همزمان دانلود می‌کند (اگر سرور پشتیبانی کند)
 * - توقف / ادامه / لغو با ذخیره وضعیت بخش‌ها
 * - ادامه دانلود حتی بعد از بسته شدن اپ (فایل‌های .part و وضعیت chunkها روی دیسک)
 */
object DownloadManager {

    const val THREAD_COUNT = 8
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"

    private const val BUFFER_SIZE = 64 * 1024
    private const val MIN_CHUNK_SIZE = 256L * 1024
    private const val MIN_CHUNKED_SIZE = 1024L * 1024
    private const val MAX_RETRIES = 5

    private lateinit var appContext: Context
    private lateinit var client: OkHttpClient
    private lateinit var partsDir: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ConcurrentHashMap<String, RunningTask>()
    private val pausedIds = ConcurrentHashMap.newKeySet<String>()
    private val canceledIds = ConcurrentHashMap.newKeySet<String>()

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        partsDir = File(appContext.filesDir, "downloads").apply { mkdirs() }
        client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        loadPersisted()
        scope.launch { speedSampler() }
    }

    // ---------------------------------------------------------------
    // API عمومی
    // ---------------------------------------------------------------

    /**
     * افزودن دانلود جدید. اگر لینک تکراری فعال باشد null برمی‌گرداند.
     */
    fun addDownload(
        url: String,
        fileName: String? = null,
        mime: String? = null,
        isSocial: Boolean = false,
        platform: SocialPlatform? = null
    ): String? {
        requireInitialized()
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return null
        if (_items.value.any { it.url == cleanUrl && it.isActive }) return null

        val name = fileName ?: guessFileName(cleanUrl)
        var id = md5(cleanUrl + "|" + name)
        if (_items.value.any { it.id == id }) {
            id = md5(cleanUrl + "|" + name + "|" + System.nanoTime())
        }

        val item = DownloadItem(
            id = id,
            url = cleanUrl,
            fileName = name,
            mime = mime ?: guessMime(name),
            isSocial = isSocial,
            platform = platform?.name
        )
        _items.update { it + item }
        persist()
        ensureService()
        scope.launch { startTask(item, resume = false) }
        return id
    }

    fun pause(id: String) {
        pausedIds.add(id)
        tasks[id]?.let { task ->
            task.paused.set(true)
            task.cancelCalls()
        }
        _items.update { list ->
            list.map {
                if (it.id == id && it.status != DownloadStatus.COMPLETED) {
                    it.copy(status = DownloadStatus.PAUSED)
                } else it
            }
        }
        persist()
    }

    fun resume(id: String) {
        pausedIds.remove(id)
        _items.update { list ->
            list.map {
                if (it.id == id) it.copy(status = DownloadStatus.QUEUED, errorMessage = null) else it
            }
        }
        ensureService()
        scope.launch {
            val task = tasks[id]
            if (task != null) {
                // نسخه جدیدتر از اجرا ثبت می‌شود تا اجرای قبلی (در حال خروج) بی‌اثر شود
                task.generation.incrementAndGet()
                task.paused.set(false)
                task.canceled.set(false)
                runTask(task)
            } else {
                val item = _items.value.firstOrNull { it.id == id } ?: return@launch
                startTask(item, resume = true)
            }
        }
    }

    fun cancel(id: String) {
        canceledIds.add(id)
        tasks[id]?.let { task ->
            task.canceled.set(true)
            task.paused.set(true)
            task.cancelCalls()
        }
        tasks.remove(id)
        scope.launch {
            File(partsDir, "$id.part").delete()
            File(partsDir, "$id.chunks.json").delete()
            _items.update { list -> list.filterNot { it.id == id } }
            persist()
        }
    }

    fun removeCompleted(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun ensureService() {
        DownloadService.start(appContext)
    }

    // ---------------------------------------------------------------
    // هسته اجرای تسک
    // ---------------------------------------------------------------

    private suspend fun startTask(item: DownloadItem, resume: Boolean) {
        try {
            updateItem(item.id) { it.copy(status = DownloadStatus.CONNECTING) }
            val probeResult = probe(item.url)
            if (canceledIds.remove(item.id)) {
                _items.update { list -> list.filterNot { it.id == item.id } }
                return
            }

            val mime = item.mime
                ?: probeResult.mime?.substringBefore(';')
                    ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: guessMime(item.fileName)
            val partFile = File(partsDir, "${item.id}.part")
            val stateFile = File(partsDir, "${item.id}.chunks.json")
            val task = RunningTask(item.id, item.url, item.fileName, mime, partFile, stateFile)

            // ادامه از وضعیت ذخیره‌شده (بعد از بسته شدن اپ)
            if (resume && partFile.exists() && stateFile.exists() &&
                probeResult.acceptRanges && probeResult.total > 0
            ) {
                val chunks = loadChunks(stateFile)
                val sum = chunks.sumOf { it.downloaded }
                if (chunks.isNotEmpty() && sum in 1..probeResult.total && partFile.length() >= sum) {
                    task.totalBytes = probeResult.total
                    task.acceptRanges = true
                    task.chunkStates.addAll(chunks)
                    task.downloaded.set(sum)
                }
            }

            if (task.chunkStates.isEmpty()) {
                task.acceptRanges = probeResult.acceptRanges && probeResult.total > 0
                task.totalBytes = if (probeResult.total > 0) probeResult.total else 0
                if (task.acceptRanges && task.totalBytes >= MIN_CHUNKED_SIZE) {
                    buildChunks(task, THREAD_COUNT)
                } else {
                    // تک‌تردی: سرور Range ندارد یا فایل کوچک است
                    task.acceptRanges = false
                    val end = if (task.totalBytes > 0) task.totalBytes - 1 else Long.MAX_VALUE - 1
                    task.chunkStates.add(ChunkState(0, end))
                }
            }

            // فضای فایل را از قبل رزرو کن تا نوشتن همزمان تداخل نداشته باشد
            if (task.totalBytes > 0 && partFile.length() < task.totalBytes) {
                RandomAccessFile(partFile, "rw").use { it.setLength(task.totalBytes) }
            }

            tasks[item.id] = task
            updateItem(item.id) { it.copy(totalBytes = task.totalBytes, mime = mime) }

            if (pausedIds.contains(item.id)) {
                updateItem(item.id) { it.copy(status = DownloadStatus.PAUSED) }
                saveChunks(task)
                return
            }
            runTask(task)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tasks.remove(item.id)
            updateItem(item.id) {
                it.copy(status = DownloadStatus.FAILED, errorMessage = e.message ?: "خطای ناشناخته")
            }
            persist()
        }
    }

    private fun buildChunks(task: RunningTask, threadCount: Int) {
        val total = task.totalBytes
        val chunkSize = maxOf(MIN_CHUNK_SIZE, total / threadCount)
        var start = 0L
        while (start < total) {
            val end = minOf(start + chunkSize - 1, total - 1)
            task.chunkStates.add(ChunkState(start, end))
            start = end + 1
        }
    }

    private suspend fun runTask(task: RunningTask) {
        val generation = task.generation.get()
        task.mutex.lock()
        try {
            if (task.generation.get() != generation) return

            updateItem(task.id) { it.copy(status = DownloadStatus.DOWNLOADING) }

            supervisorScope {
                val jobs = mutableListOf<Job>()
                synchronized(task.lock) {
                    task.chunkStates.forEach { chunk ->
                        if (!chunk.done) jobs.add(launch { downloadChunk(task, chunk) })
                    }
                }
                jobs.joinAll()
            }

            // اگر نسخه جدیدتری (ادامه مجدد) راه افتاده، اجرای فعلی بی‌اثر است
            if (task.generation.get() != generation) return

            when {
                task.canceled.get() -> {
                    task.partFile.delete()
                    task.stateFile.delete()
                    _items.update { list -> list.filterNot { it.id == task.id } }
                    tasks.remove(task.id)
                    persist()
                }
                task.paused.get() -> {
                    saveChunks(task)
                    updateItem(task.id) { it.copy(status = DownloadStatus.PAUSED) }
                    persist()
                }
                task.error.get() != null -> {
                    tasks.remove(task.id)
                    updateItem(task.id) {
                        it.copy(status = DownloadStatus.FAILED, errorMessage = task.error.get())
                    }
                    persist()
                }
                else -> finishCompleted(task)
            }
        } finally {
            task.mutex.unlock()
        }
    }

    private suspend fun downloadChunk(task: RunningTask, chunk: ChunkState) {
        var retries = 0
        while (!chunk.done && !task.canceled.get() && !task.paused.get() && task.error.get() == null) {
            // در حالت تک‌تردی بدون Range، ادامه از وسط ممکن نیست — از صفر شروع کن
            if (!task.acceptRanges && chunk.downloaded > 0) {
                task.downloaded.addAndGet(-chunk.downloaded)
                chunk.downloaded = 0
            }
            val raf = RandomAccessFile(task.partFile, "rw")
            try {
                val from = chunk.start + chunk.downloaded
                if (task.acceptRanges && from > chunk.end) {
                    chunk.done = true
                    break
                }
                val requestBuilder = Request.Builder()
                    .url(task.url)
                    .header("User-Agent", USER_AGENT)
                if (task.acceptRanges) requestBuilder.header("Range", "bytes=$from-${chunk.end}")
                val call = client.newCall(requestBuilder.build())
                synchronized(task.lock) { task.calls.add(call) }
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw IOException("کد پاسخ HTTP ${response.code}")
                        if (task.acceptRanges && from > 0 && response.code == 200) {
                            throw IOException("سرور دانلود چندبخشی را پشتیبانی نمی‌کند")
                        }
                        val body = response.body ?: throw IOException("پاسخ بدون بدنه")
                        val input = body.byteStream()
                        raf.seek(from)
                        val buffer = ByteArray(BUFFER_SIZE)
                        var eof = false
                        while (!task.canceled.get() && !task.paused.get() && task.error.get() == null) {
                            val n = input.read(buffer)
                            if (n == -1) {
                                eof = true
                                break
                            }
                            raf.write(buffer, 0, n)
                            chunk.downloaded += n
                            task.downloaded.addAndGet(n.toLong())
                        }
                        if (task.acceptRanges) {
                            // اگر سرور بیشتر از محدوده فرستاده، کسر کن
                            if (chunk.start + chunk.downloaded > chunk.end + 1) {
                                val over = chunk.start + chunk.downloaded - (chunk.end + 1)
                                chunk.downloaded -= over
                                task.downloaded.addAndGet(-over)
                                raf.setLength(task.totalBytes)
                            }
                            if (chunk.downloaded >= chunk.size && chunk.size > 0) chunk.done = true
                        } else {
                            if (eof) chunk.done = true
                        }
                    }
                } finally {
                    synchronized(task.lock) { task.calls.remove(call) }
                }
                if (chunk.done) break
                if (!task.paused.get() && !task.canceled.get() && task.error.get() == null) {
                    throw IOException("دریافت داده ناقص ماند")
                }
            } catch (e: Exception) {
                if (task.canceled.get() || task.paused.get() || task.error.get() != null) break
                retries++
                if (retries >= MAX_RETRIES) {
                    task.error.compareAndSet(
                        null,
                        "خطا در دانلود: ${e.message ?: "نامشخص"}"
                    )
                    break
                }
                delay(800L * retries)
            } finally {
                runCatching { raf.close() }
            }
        }
    }

    private fun finishCompleted(task: RunningTask) {
        try {
            val finalBytes = if (task.totalBytes > 0) task.totalBytes else task.downloaded.get()
            val uri = StorageSaver.saveToPublicDownloads(appContext, task.partFile, task.fileName, task.mime)
            task.partFile.delete()
            task.stateFile.delete()
            tasks.remove(task.id)
            updateItem(task.id) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    downloadedBytes = finalBytes,
                    totalBytes = finalBytes,
                    savedUri = uri,
                    errorMessage = null
                )
            }
            persist()
        } catch (e: Exception) {
            tasks.remove(task.id)
            updateItem(task.id) {
                it.copy(status = DownloadStatus.FAILED, errorMessage = "ذخیره فایل ناموفق بود: ${e.message ?: ""}")
            }
            persist()
        }
    }

    // ---------------------------------------------------------------
    // کشف مشخصات فایل
    // ---------------------------------------------------------------

    private class Probe(val total: Long, val acceptRanges: Boolean, val mime: String?)

    private fun probe(url: String): Probe {
        var lastError: Exception? = null
        try {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", USER_AGENT).head().build()
            ).execute().use { resp ->
                if (resp.isSuccessful) {
                    val len = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    val ar = resp.header("Accept-Ranges")?.equals("bytes", true) == true ||
                            resp.header("Content-Range") != null
                    return Probe(len, ar, resp.header("Content-Type"))
                }
            }
        } catch (e: Exception) {
            lastError = e
        }
        try {
            client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=0-0")
                    .build()
            ).execute().use { resp ->
                val cr = resp.header("Content-Range")
                if (resp.code == 206 && cr != null) {
                    val total = cr.substringAfterLast('/').toLongOrNull() ?: -1L
                    return Probe(total, true, resp.header("Content-Type"))
                }
                if (resp.isSuccessful) {
                    val len = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    return Probe(len, false, resp.header("Content-Type"))
                }
                lastError = IOException("کد پاسخ HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            lastError = e
        }
        throw IOException("اتصال به سرور ممکن نشد${lastError?.message?.let { ": $it" } ?: ""}")
    }

    // ---------------------------------------------------------------
    // سرعت‌سنج
    // ---------------------------------------------------------------

    private suspend fun speedSampler() {
        val lastBytes = HashMap<String, Long>()
        var tick = 0
        while (currentCoroutineContext().isActive) {
            delay(500)
            val current = _items.value
            val updated = current.map { item ->
                val task = tasks[item.id]
                if (task == null || item.status != DownloadStatus.DOWNLOADING) {
                    if (item.speedBps != 0L) item.copy(speedBps = 0) else item
                } else {
                    val now = task.downloaded.get()
                    val prev = lastBytes[item.id] ?: now
                    val bps = ((now - prev) * 2).coerceIn(0, 512L * 1024 * 1024)
                    lastBytes[item.id] = now
                    val ema = if (item.speedBps == 0L) bps else (bps * 0.4 + item.speedBps * 0.6).toLong()
                    item.copy(downloadedBytes = now, speedBps = ema)
                }
            }
            _items.value = updated
            val ids = current.map { it.id }.toSet()
            lastBytes.keys.removeAll { it !in ids }
            if (++tick % 20 == 0) persist()
        }
    }

    // ---------------------------------------------------------------
    // ابزارهای کمکی
    // ---------------------------------------------------------------

    private fun updateItem(id: String, transform: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    fun guessMime(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "apk" -> "application/vnd.android.package-archive"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            else -> "application/octet-stream"
        }
    }

    private fun guessFileName(url: String): String {
        return try {
            val path = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
            val decoded = URLDecoder.decode(path, "UTF-8")
            if (decoded.isNotBlank() && decoded.contains('.')) decoded
            else "download_${System.currentTimeMillis()}.bin"
        } catch (e: Exception) {
            "download_${System.currentTimeMillis()}.bin"
        }
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun requireInitialized() {
        check(initialized) { "DownloadManager.init باید در Application صدا زده شود" }
    }

    // ---------------------------------------------------------------
    // ذخیره‌سازی وضعیت
    // ---------------------------------------------------------------

    private fun persist() {
        try {
            val arr = JSONArray()
            _items.value.forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("url", item.url)
                        put("fileName", item.fileName)
                        put("totalBytes", item.totalBytes)
                        put("downloadedBytes", item.downloadedBytes)
                        put("status", item.status.name)
                        put("createdAt", item.createdAt)
                        put("isSocial", item.isSocial)
                        put("platform", item.platform ?: "")
                        put("mime", item.mime ?: "")
                        put("savedUri", item.savedUri ?: "")
                    }
                )
            }
            File(appContext.filesDir, "items.json").writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun loadPersisted() {
        try {
            val file = File(appContext.filesDir, "items.json")
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            val list = mutableListOf<DownloadItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                var status = try {
                    DownloadStatus.valueOf(o.getString("status"))
                } catch (e: Exception) {
                    DownloadStatus.FAILED
                }
                if (status == DownloadStatus.QUEUED ||
                    status == DownloadStatus.CONNECTING ||
                    status == DownloadStatus.DOWNLOADING
                ) status = DownloadStatus.PAUSED
                list.add(
                    DownloadItem(
                        id = o.getString("id"),
                        url = o.getString("url"),
                        fileName = o.getString("fileName"),
                        totalBytes = o.optLong("totalBytes"),
                        downloadedBytes = o.optLong("downloadedBytes"),
                        status = status,
                        createdAt = o.optLong("createdAt"),
                        isSocial = o.optBoolean("isSocial"),
                        platform = o.optString("platform").ifEmpty { null },
                        mime = o.optString("mime").ifEmpty { null },
                        savedUri = o.optString("savedUri").ifEmpty { null }
                    )
                )
            }
            _items.value = list
        } catch (_: Exception) {
        }
    }

    private fun saveChunks(task: RunningTask) {
        if (!task.acceptRanges) return
        try {
            val arr = JSONArray()
            synchronized(task.lock) {
                task.chunkStates.forEach { c ->
                    arr.put(
                        JSONObject().apply {
                            put("s", c.start)
                            put("e", c.end)
                            put("d", c.downloaded)
                        }
                    )
                }
            }
            task.stateFile.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun loadChunks(file: File): List<ChunkState> {
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val s = o.getLong("s")
                val e = o.getLong("e")
                val d = o.optLong("d")
                val c = ChunkState(s, e, d)
                c.done = d >= (e - s + 1)
                c
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * وضعیت در حال اجرای یک دانلود
     */
    private class RunningTask(
        val id: String,
        val url: String,
        val fileName: String,
        val mime: String,
        val partFile: File,
        val stateFile: File
    ) {
        val lock = Any()
        val mutex = Mutex()
        val generation = AtomicLong(0)
        val paused = AtomicBoolean(false)
        val canceled = AtomicBoolean(false)
        val error = AtomicReference<String?>(null)
        val downloaded = AtomicLong(0)
        val chunkStates = ArrayList<ChunkState>()
        val calls = ArrayList<Call>()

        @Volatile
        var totalBytes: Long = 0

        @Volatile
        var acceptRanges: Boolean = false

        fun cancelCalls() {
            synchronized(lock) {
                calls.forEach { runCatching { it.cancel() } }
                calls.clear()
            }
        }
    }
}
