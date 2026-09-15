using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace SakhandDm.Core;

/// <summary>
/// موتور دانلود چندتردی Download Manager برای ویندوز
/// - فایل را تا ۳۲ بخش تقسیم و همزمان دانلود می‌کند (اگر سرور پشتیبانی کند)
/// - توقف / ادامه / لغو با ذخیره وضعیت بخش‌ها
/// - ادامه دانلود حتی بعد از بسته شدن برنامه (فایل .part و وضعیت chunkها روی دیسک)
/// </summary>
public sealed class DownloadEngine
{
    public const int ThreadCount = 32;
    public const string UserAgent = SocialResolver.UserAgent;

    private const int BufferSize = 512 * 1024;
    private const long MinChunkSize = 1024L * 1024;
    private const long MinChunkedSize = 1024L * 1024;
    private const int MaxRetries = 5;

    private readonly string _appData;
    private readonly string _partsDir;
    private readonly HttpClient _client;

    private readonly object _itemsLock = new();
    private readonly List<DownloadItem> _items = new();
    private readonly ConcurrentDictionary<string, RunningTask> _tasks = new();

    /// <summary>رویداد کلی تغییر لیست (افزودن/حذف/تغییر وضعیت‌ها)</summary>
    public event Action? ItemsChanged;
    /// <summary>رویداد به‌روزرسانی یک آیتم (پیشرفت، سرعت، وضعیت)</summary>
    public event Action<string>? ItemUpdated;

    public DownloadEngine(string appData)
    {
        _appData = appData;
        _partsDir = Path.Combine(appData, "parts");
        Directory.CreateDirectory(_partsDir);

        var handler = new SocketsHttpHandler
        {
            ConnectTimeout = TimeSpan.FromSeconds(20),
            AllowAutoRedirect = true,
            UseCookies = false,
            AutomaticDecompression = System.Net.DecompressionMethods.None
        };
        _client = new HttpClient(handler) { Timeout = TimeSpan.FromMinutes(10) };
        _client.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", UserAgent);
    }

    public static string DefaultAppDataDir()
    {
        var root = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(root, "SakhandDownloadManager");
    }

    public static string DownloadsDir()
    {
        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
    }

    // ---------------------------------------------------------------
    // API عمومی
    // ---------------------------------------------------------------

    public List<DownloadItem> Snapshot()
    {
        lock (_itemsLock) return _items.Select(i => i).ToList();
    }

    /// <summary>افزودن دانلود جدید. اگر لینک تکراری فعال باشد null برمی‌گرداند.</summary>
    public string? Add(string url, string? fileName = null, string? mime = null,
        bool isSocial = false, string? platform = null)
    {
        var cleanUrl = url.Trim();
        if (cleanUrl.Length == 0) return null;

        lock (_itemsLock)
        {
            if (_items.Any(i => i.Url == cleanUrl && i.IsActive)) return null;
        }

        var name = SanitizeFileName(fileName ?? GuessFileName(cleanUrl));
        var id = Md5(cleanUrl + "|" + name);
        lock (_itemsLock)
        {
            if (_items.Any(i => i.Id == id)) id = Md5(cleanUrl + "|" + name + "|" + Guid.NewGuid().ToString("N"));
        }

        var item = new DownloadItem
        {
            Id = id,
            Url = cleanUrl,
            FileName = name,
            Mime = mime ?? GuessMime(name),
            IsSocial = isSocial,
            Platform = platform
        };
        lock (_itemsLock) _items.Add(item);
        Persist();
        RaiseItemsChanged();

        _ = Task.Run(() => StartTaskAsync(item, resume: false));
        return id;
    }

    public void Pause(string id)
    {
        if (_tasks.TryGetValue(id, out var task))
        {
            task.Paused = true;
            task.CancelCurrent();
        }
        lock (_itemsLock)
        {
            foreach (var it in _items.Where(i => i.Id == id && i.Status != DownloadStatus.Completed))
                it.Status = DownloadStatus.Paused;
        }
        Persist();
        RaiseItemUpdated(id);
        RaiseItemsChanged();
    }

    public void Resume(string id)
    {
        UpdateItem(id, it => { it.Status = DownloadStatus.Queued; it.ErrorMessage = null; });
        _ = Task.Run(async () =>
        {
            if (_tasks.TryGetValue(id, out var task))
            {
                Interlocked.Increment(ref task.GenerationField);
                task.Paused = false;
                task.Canceled = false;
                await RunTaskAsync(task);
            }
            else
            {
                var item = FindItem(id);
                if (item == null) return;
                await StartTaskAsync(item, resume: true);
            }
        });
    }

    public void Cancel(string id)
    {
        if (_tasks.TryGetValue(id, out var task))
        {
            task.Canceled = true;
            task.Paused = true;
            task.CancelCurrent();
            _tasks.TryRemove(id, out _);
        }
        try
        {
            File.Delete(Path.Combine(_partsDir, id + ".part"));
            File.Delete(Path.Combine(_partsDir, id + ".chunks.json"));
        }
        catch { /* فایل قفل نباشد مشکلی نیست */ }
        lock (_itemsLock) _items.RemoveAll(i => i.Id == id);
        Persist();
        RaiseItemsChanged();
    }

    public void RemoveCompleted(string id)
    {
        lock (_itemsLock) _items.RemoveAll(i => i.Id == id);
        Persist();
        RaiseItemsChanged();
    }

    /// <summary>
    /// تغییر نام فایل. برای حالت تکمیل‌شده فایل روی دیسک هم تغییر نام می‌یابد.
    /// در صورت خطا پیام فارسی برمی‌گرداند؛ موفق یعنی null.
    /// </summary>
    public string? Rename(string id, string newNameRaw)
    {
        var newName = SanitizeFileName(newNameRaw.Trim());
        if (newName.Length == 0) return "نام فایل نمی‌تواند خالی باشد";

        DownloadItem? item;
        lock (_itemsLock) item = _items.FirstOrDefault(i => i.Id == id);
        if (item == null) return "این دانلود پیدا نشد";
        if (item.IsActive) return "ابتدا دانلود را متوقف کن یا منتظر بمان تکمیل شود";

        try
        {
            if (item.Status == DownloadStatus.Completed && item.SavedPath != null && File.Exists(item.SavedPath))
            {
                var dir = Path.GetDirectoryName(item.SavedPath)!;
                var target = UniquePath(Path.Combine(dir, newName));
                if (!string.Equals(item.SavedPath, target, StringComparison.OrdinalIgnoreCase))
                {
                    if (File.Exists(target)) return "فایلی با این نام از قبل وجود دارد";
                    File.Move(item.SavedPath, target);
                    item.SavedPath = target;
                }
            }

            item.FileName = newName;
            Persist();
            RaiseItemUpdated(id);
            RaiseItemsChanged();
            return null;
        }
        catch (Exception ex)
        {
            return "تغییر نام ممکن نشد: " + ex.Message;
        }
    }

    /// <summary>مسیر فایل قابل نمایش برای «باز کردن پوشه»</summary>
    public string? OpenablePath(string id)
    {
        DownloadItem? item;
        lock (_itemsLock) item = _items.FirstOrDefault(i => i.Id == id);
        if (item == null) return null;
        if (item.Status == DownloadStatus.Completed && item.SavedPath != null && File.Exists(item.SavedPath))
            return item.SavedPath;
        return null;
    }

    public void LoadPersisted()
    {
        try
        {
            var file = Path.Combine(_appData, "items.json");
            if (!File.Exists(file)) return;
            var arr = System.Text.Json.JsonDocument.Parse(File.ReadAllText(file)).RootElement;
            var list = new List<DownloadItem>();
            foreach (var o in arr.EnumerateArray())
            {
                var status = Enum.TryParse<DownloadStatus>(o.GetProperty("status").GetString(), out var st)
                    ? st : DownloadStatus.Failed;
                if (status is DownloadStatus.Queued or DownloadStatus.Connecting or DownloadStatus.Downloading)
                    status = DownloadStatus.Paused;

                string GetStr(string p) => o.TryGetProperty(p, out var el) ? (el.GetString() ?? "") : "";
                long GetLong(string p) => o.TryGetProperty(p, out var el) && el.TryGetInt64(out var lv) ? lv : 0;
                bool GetBool(string p) => o.TryGetProperty(p, out var el) && el.ValueKind == System.Text.Json.JsonValueKind.True;

                list.Add(new DownloadItem
                {
                    Id = GetStr("id"),
                    Url = GetStr("url"),
                    FileName = GetStr("fileName"),
                    TotalBytes = GetLong("totalBytes"),
                    DownloadedBytes = GetLong("downloadedBytes"),
                    Status = status,
                    CreatedAt = GetLong("createdAt"),
                    IsSocial = GetBool("isSocial"),
                    Platform = GetStr("platform") is { Length: > 0 } p ? p : null,
                    Mime = GetStr("mime") is { Length: > 0 } m ? m : null,
                    SavedPath = GetStr("savedPath") is { Length: > 0 } sp ? sp : null
                });
            }
            lock (_itemsLock)
            {
                _items.Clear();
                _items.AddRange(list);
            }
            RaiseItemsChanged();
        }
        catch { /* خرابی وضعیت نباید برنامه را ببندد */ }
    }

    public void PauseAllForExit()
    {
        List<string> ids;
        lock (_itemsLock) ids = _items.Where(i => i.IsActive).Select(i => i.Id).ToList();
        foreach (var id in ids) Pause(id);
    }

    // ---------------------------------------------------------------
    // هسته اجرای تسک
    // ---------------------------------------------------------------

    private async Task StartTaskAsync(DownloadItem item, bool resume)
    {
        try
        {
            UpdateItem(item.Id, it => { it.Status = DownloadStatus.Connecting; });
            RaiseItemUpdated(item.Id);

            var probe = await ProbeAsync(item.Url);

            var partFile = new FileInfo(Path.Combine(_partsDir, item.Id + ".part"));
            var stateFile = new FileInfo(Path.Combine(_partsDir, item.Id + ".chunks.json"));
            var task = new RunningTask(item.Id, item.Url, partFile.FullName, stateFile.FullName, item.FileName);

            // ادامه از وضعیت ذخیره‌شده (بعد از بسته شدن برنامه)
            if (resume && File.Exists(partFile.FullName) && File.Exists(stateFile.FullName) &&
                probe.AcceptRanges && probe.Total > 0)
            {
                var chunks = LoadChunks(stateFile.FullName);
                var sum = chunks.Sum(c => c.Downloaded);
                if (chunks.Count > 0 && sum > 0 && sum <= probe.Total && new FileInfo(partFile.FullName).Length >= sum)
                {
                    task.TotalBytes = probe.Total;
                    task.AcceptRanges = true;
                    task.Chunks.AddRange(chunks);
                    Interlocked.Add(ref task.DownloadedField, sum);
                }
            }

            if (task.Chunks.Count == 0)
            {
                task.AcceptRanges = probe.AcceptRanges && probe.Total > 0;
                task.TotalBytes = probe.Total > 0 ? probe.Total : 0;
                if (task.AcceptRanges && task.TotalBytes >= MinChunkedSize)
                {
                    BuildChunks(task, ThreadCount);
                }
                else
                {
                    // تک‌تردی: سرور Range ندارد یا فایل کوچک است
                    task.AcceptRanges = false;
                    var end = task.TotalBytes > 0 ? task.TotalBytes - 1 : long.MaxValue - 1;
                    task.Chunks.Add(new ChunkState(0, end));
                }
            }

            // رزرو فضا از قبل برای نوشتن همزمان
            if (task.TotalBytes > 0 && partFile.Exists && partFile.Length < task.TotalBytes)
            {
                await using (var fs = new FileStream(partFile.FullName, FileMode.OpenOrCreate, FileAccess.Write, FileShare.Read))
                    fs.SetLength(task.TotalBytes);
            }

            _tasks[item.Id] = task;
            UpdateItem(item.Id, it => { it.TotalBytes = task.TotalBytes; });
            RaiseItemUpdated(item.Id);

            await RunTaskAsync(task);
        }
        catch (Exception ex)
        {
            _tasks.TryRemove(item.Id, out _);
            UpdateItem(item.Id, it =>
            {
                it.Status = DownloadStatus.Failed;
                it.ErrorMessage = ex.Message.Length > 0 ? ex.Message : "خطای ناشناخته";
            });
            Persist();
            RaiseItemUpdated(item.Id);
            RaiseItemsChanged();
        }
    }

    private void BuildChunks(RunningTask task, int threadCount)
    {
        var total = task.TotalBytes;
        var chunkSize = Math.Max(MinChunkSize, total / threadCount);
        long start = 0;
        while (start < total)
        {
            var end = Math.Min(start + chunkSize - 1, total - 1);
            task.Chunks.Add(new ChunkState(start, end));
            start = end + 1;
        }
    }

    private async Task RunTaskAsync(RunningTask task)
    {
        var generation = Interlocked.CompareExchange(ref task.GenerationField, 0, 0);
        lock (task.Mutex)
        {
            if (Interlocked.CompareExchange(ref task.GenerationField, 0, 0) != generation) return;
        }

        UpdateItem(task.Id, it => { it.Status = DownloadStatus.Downloading; });
        RaiseItemUpdated(task.Id);

        using var cts = new CancellationTokenSource();
        task.SetCts(cts);
        var token = cts.Token;

        var workers = new List<Task>();
        lock (task.ChunkLock)
        {
            foreach (var chunk in task.Chunks.Where(c => !c.Done))
                workers.Add(Task.Run(() => DownloadChunkAsync(task, chunk, token), token));
        }
        try { await Task.WhenAll(workers); }
        catch (OperationCanceledException) { }
        catch { /* خطای تکی داخل DownloadChunkAsync مدیریت می‌شود */ }

        if (Interlocked.CompareExchange(ref task.GenerationField, 0, 0) != generation) return;

        if (task.Canceled)
        {
            try { File.Delete(task.PartPath); File.Delete(task.StatePath); } catch { }
            _tasks.TryRemove(task.Id, out _);
            lock (_itemsLock) _items.RemoveAll(i => i.Id == task.Id);
            Persist();
            RaiseItemsChanged();
        }
        else if (task.Paused)
        {
            SaveChunks(task);
            UpdateItem(task.Id, it => { it.Status = DownloadStatus.Paused; });
            Persist();
            RaiseItemUpdated(task.Id);
            RaiseItemsChanged();
        }
        else if (task.Error != null)
        {
            _tasks.TryRemove(task.Id, out _);
            UpdateItem(task.Id, it =>
            {
                it.Status = DownloadStatus.Failed;
                it.ErrorMessage = task.Error;
            });
            Persist();
            RaiseItemUpdated(task.Id);
            RaiseItemsChanged();
        }
        else
        {
            FinishCompleted(task);
        }
    }

    private async Task DownloadChunkAsync(RunningTask task, ChunkState chunk, CancellationToken token)
    {
        var retries = 0;
        while (!chunk.Done && !task.Canceled && !task.Paused && task.Error == null)
        {
            // در حالت تک‌تردی بدون Range، ادامه از وسط ممکن نیست — از صفر شروع کن
            if (!task.AcceptRanges && chunk.Downloaded > 0)
            {
                Interlocked.Add(ref task.DownloadedField, -chunk.Downloaded);
                chunk.Downloaded = 0;
            }

            FileStream? fs = null;
            try
            {
                fs = new FileStream(task.PartPath, FileMode.OpenOrCreate, FileAccess.Write, FileShare.Read);
                var from = chunk.Start + chunk.Downloaded;
                if (task.AcceptRanges && from > chunk.End)
                {
                    chunk.Done = true;
                    break;
                }

                using var req = new HttpRequestMessage(HttpMethod.Get, task.Url);
                if (task.AcceptRanges) req.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(from, chunk.End);

                using var resp = await _client.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, token);
                if ((int)resp.StatusCode is < 200 or >= 300)
                    throw new IOException($"کد پاسخ HTTP {(int)resp.StatusCode}");
                if (task.AcceptRanges && from > 0 && (int)resp.StatusCode == 200)
                    throw new IOException("سرور دانلود چندبخشی را پشتیبانی نمی‌کند");

                await using var input = await resp.Content.ReadAsStreamAsync(token);
                var buffer = new byte[BufferSize];
                fs.Seek(from, SeekOrigin.Begin);
                var eof = false;

                while (!task.Canceled && !task.Paused && task.Error == null)
                {
                    int n;
                    try { n = await input.ReadAsync(buffer, token); }
                    catch (OperationCanceledException) when (!token.IsCancellationRequested)
                    {
                        throw new IOException("اتصال به سرور قطع شد");
                    }
                    if (n == 0) { eof = true; break; }

                    lock (task.WriteLock)
                    {
                        fs.Seek(chunk.Start + chunk.Downloaded, SeekOrigin.Begin);
                        fs.Write(buffer, 0, n);
                    }
                    chunk.Downloaded += n;
                    Interlocked.Add(ref task.DownloadedField, n);
                }

                if (task.AcceptRanges)
                {
                    // اگر سرور بیشتر از محدوده فرستاده، کسر کن
                    if (chunk.Start + chunk.Downloaded > chunk.End + 1)
                    {
                        var over = chunk.Start + chunk.Downloaded - (chunk.End + 1);
                        chunk.Downloaded -= over;
                        Interlocked.Add(ref task.DownloadedField, -over);
                    }
                    if (chunk.Downloaded >= chunk.Size && chunk.Size > 0) chunk.Done = true;
                }
                else if (eof)
                {
                    chunk.Done = true;
                }

                if (chunk.Done) break;
                if (!task.Paused && !task.Canceled && task.Error == null)
                    throw new IOException("دریافت داده ناقص ماند");
            }
            catch (OperationCanceledException) when (task.Paused || task.Canceled)
            {
                break;
            }
            catch (Exception ex)
            {
                if (task.Canceled || task.Paused || task.Error != null) break;
                retries++;
                if (retries >= MaxRetries)
                {
                    task.SetError("خطا در دانلود: " + ex.Message);
                    break;
                }
                try { await Task.Delay(800 * retries, token); }
                catch (OperationCanceledException) { break; }
            }
            finally
            {
                try { fs?.Dispose(); } catch { }
            }
        }
    }

    private void FinishCompleted(RunningTask task)
    {
        try
        {
            var finalBytes = task.TotalBytes > 0 ? task.TotalBytes : Interlocked.Read(ref task.DownloadedField);
            var destDir = DownloadsDir();
            Directory.CreateDirectory(destDir);
            var target = UniquePath(Path.Combine(destDir, SanitizeFileName(task.FileName)));

            File.Move(task.PartPath, target, overwrite: false);
            try { File.Delete(task.StatePath); } catch { }
            _tasks.TryRemove(task.Id, out _);

            UpdateItem(task.Id, it =>
            {
                it.Status = DownloadStatus.Completed;
                it.DownloadedBytes = finalBytes;
                it.TotalBytes = finalBytes;
                it.SavedPath = target;
                it.ErrorMessage = null;
                it.SpeedBps = 0;
            });
            Persist();
            RaiseItemUpdated(task.Id);
            RaiseItemsChanged();
        }
        catch (Exception ex)
        {
            _tasks.TryRemove(task.Id, out _);
            UpdateItem(task.Id, it =>
            {
                it.Status = DownloadStatus.Failed;
                it.ErrorMessage = "ذخیره فایل ناموفق بود: " + ex.Message;
            });
            Persist();
            RaiseItemUpdated(task.Id);
            RaiseItemsChanged();
        }
    }

    // ---------------------------------------------------------------
    // کشف مشخصات فایل
    // ---------------------------------------------------------------

    private sealed record Probe(long Total, bool AcceptRanges, string? Mime);

    private async Task<Probe> ProbeAsync(string url)
    {
        Exception? lastError = null;
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Head, url);
            using var resp = await _client.SendAsync(req, HttpCompletionOption.ResponseHeadersRead);
            if ((int)resp.StatusCode is >= 200 and < 300)
            {
                var len = ParseLong(resp.Content.Headers.ContentLength);
                var ar = string.Equals(resp.Headers.AcceptRanges.FirstOrDefault(), "bytes", StringComparison.OrdinalIgnoreCase)
                         || resp.Content.Headers.ContentRange?.Length != null;
                return new Probe(len, ar, resp.Content.Headers.ContentType?.ToString());
            }
            lastError = new IOException($"کد پاسخ HTTP {(int)resp.StatusCode}");
        }
        catch (Exception ex) { lastError = ex; }

        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            req.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(0, 0);
            using var resp = await _client.SendAsync(req, HttpCompletionOption.ResponseHeadersRead);
            var cr = resp.Content.Headers.ContentRange;
            if ((int)resp.StatusCode == 206 && cr?.Length != null)
                return new Probe(cr.Length.Value, true, resp.Content.Headers.ContentType?.ToString());
            if ((int)resp.StatusCode is >= 200 and < 300)
            {
                var len = ParseLong(resp.Content.Headers.ContentLength);
                return new Probe(len, false, resp.Content.Headers.ContentType?.ToString());
            }
            lastError = new IOException($"کد پاسخ HTTP {(int)resp.StatusCode}");
        }
        catch (Exception ex) { lastError = ex; }

        throw new IOException("اتصال به سرور ممکن نشد" + (lastError != null ? ": " + lastError.Message : ""));
    }

    // ---------------------------------------------------------------
    // سرعت‌سنج
    // ---------------------------------------------------------------

    private long _tick;
    private readonly Dictionary<string, long> _lastBytes = new();

    public void StartSpeedSampler()
    {
        _ = Task.Run(async () =>
        {
            while (true)
            {
                await Task.Delay(500);
                List<DownloadItem> snapshot;
                lock (_itemsLock) snapshot = _items.ToList();
                var changed = false;
                lock (_lastBytes)
                {
                    var current = new Dictionary<string, long>();
                    foreach (var item in snapshot)
                    {
                        if (item.Status != DownloadStatus.Downloading ||
                            !_tasks.TryGetValue(item.Id, out var task))
                        {
                            if (item.SpeedBps != 0) { item.SpeedBps = 0; changed = true; }
                            continue;
                        }
                        var now = Interlocked.Read(ref task.DownloadedField);
                        var prev = _lastBytes.TryGetValue(item.Id, out var p) ? p : now;
                        var bps = Math.Clamp((now - prev) * 2, 0, 512L * 1024 * 1024);
                        current[item.Id] = now;
                        var ema = item.SpeedBps == 0 ? bps : (long)(bps * 0.4 + item.SpeedBps * 0.6);
                        if (item.SpeedBps != ema || item.DownloadedBytes != now)
                        {
                            item.SpeedBps = ema;
                            item.DownloadedBytes = now;
                            changed = true;
                        }
                        RaiseItemUpdated(item.Id);
                    }
                    var ids = snapshot.Select(i => i.Id).ToHashSet();
                    foreach (var k in _lastBytes.Keys.Where(k => !ids.Contains(k)).ToList()) _lastBytes.Remove(k);
                    _lastBytes.Clear();
                    foreach (var kv in current) _lastBytes[kv.Key] = kv.Value;
                }
                if (changed) RaiseItemsChanged();
                if (Interlocked.Increment(ref _tick) % 20 == 0) Persist();
            }
        });
    }

    // ---------------------------------------------------------------
    // ابزارهای کمکی
    // ---------------------------------------------------------------

    private DownloadItem? FindItem(string id)
    {
        lock (_itemsLock) return _items.FirstOrDefault(i => i.Id == id);
    }

    private void UpdateItem(string id, Action<DownloadItem> transform)
    {
        lock (_itemsLock)
        {
            foreach (var it in _items.Where(i => i.Id == id)) transform(it);
        }
    }

    private void RaiseItemsChanged() => ItemsChanged?.Invoke();
    private void RaiseItemUpdated(string id) => ItemUpdated?.Invoke(id);

    public static string GuessMime(string fileName)
    {
        var ext = Path.GetExtension(fileName).TrimStart('.').ToLowerInvariant();
        if (ext.Length == 0) return "application/octet-stream";
        return ext switch
        {
            "mp4" => "video/mp4",
            "mkv" => "video/x-matroska",
            "webm" => "video/webm",
            "mov" => "video/quicktime",
            "avi" => "video/x-msvideo",
            "mp3" => "audio/mpeg",
            "m4a" => "audio/mp4",
            "wav" => "audio/wav",
            "jpg" or "jpeg" => "image/jpeg",
            "png" => "image/png",
            "gif" => "image/gif",
            "webp" => "image/webp",
            "pdf" => "application/pdf",
            "zip" => "application/zip",
            "rar" => "application/vnd.rar",
            "7z" => "application/x-7z-compressed",
            "apk" => "application/vnd.android.package-archive",
            "exe" => "application/vnd.microsoft.portable-executable",
            "txt" => "text/plain",
            _ => "application/octet-stream"
        };
    }

    private static string GuessFileName(string url)
    {
        try
        {
            var path = url.Split('#')[0].Split('?')[0];
            var name = Uri.UnescapeDataString(path.Split('/').LastOrDefault() ?? "");
            if (!string.IsNullOrWhiteSpace(name) && name.Contains('.')) return name;
        }
        catch { }
        return $"download_{DateTimeOffset.Now.ToUnixTimeMilliseconds()}.bin";
    }

    public static string SanitizeFileName(string name)
    {
        var invalid = Path.GetInvalidFileNameChars();
        var sb = new StringBuilder(name.Length);
        foreach (var c in name) sb.Append(invalid.Contains(c) ? '_' : c);
        var s = sb.ToString().Trim();
        return s.Length == 0 ? "file" : s;
    }

    private static string UniquePath(string path)
    {
        if (!File.Exists(path)) return path;
        var dir = Path.GetDirectoryName(path)!;
        var name = Path.GetFileNameWithoutExtension(path);
        var ext = Path.GetExtension(path);
        for (var i = 1; i < 10000; i++)
        {
            var candidate = Path.Combine(dir, $"{name} ({i}){ext}");
            if (!File.Exists(candidate)) return candidate;
        }
        throw new IOException("پوشه دانلود پر از فایل‌های همنام است");
    }

    private static long ParseLong(long? v) => v ?? -1;

    private static string Md5(string text)
    {
        var bytes = MD5.HashData(Encoding.UTF8.GetBytes(text));
        return Convert.ToHexString(bytes).ToLowerInvariant();
    }

    // ---------------------------------------------------------------
    // ذخیره‌سازی وضعیت
    // ---------------------------------------------------------------

    private void Persist()
    {
        try
        {
            List<DownloadItem> snapshot;
            lock (_itemsLock) snapshot = _items.ToList();
            using var stream = new MemoryStream();
            using (var writer = new System.Text.Json.Utf8JsonWriter(stream))
            {
                writer.WriteStartArray();
                foreach (var item in snapshot)
                {
                    writer.WriteStartObject();
                    writer.WriteString("id", item.Id);
                    writer.WriteString("url", item.Url);
                    writer.WriteString("fileName", item.FileName);
                    writer.WriteNumber("totalBytes", item.TotalBytes);
                    writer.WriteNumber("downloadedBytes", item.DownloadedBytes);
                    writer.WriteString("status", item.Status.ToString());
                    writer.WriteNumber("createdAt", item.CreatedAt);
                    writer.WriteBoolean("isSocial", item.IsSocial);
                    writer.WriteString("platform", item.Platform ?? "");
                    writer.WriteString("mime", item.Mime ?? "");
                    writer.WriteString("savedPath", item.SavedPath ?? "");
                    writer.WriteEndObject();
                }
                writer.WriteEndArray();
            }
            Directory.CreateDirectory(_appData);
            File.WriteAllText(Path.Combine(_appData, "items.json"), Encoding.UTF8.GetString(stream.ToArray()));
        }
        catch { }
    }

    private void SaveChunks(RunningTask task)
    {
        if (!task.AcceptRanges) return;
        try
        {
            using var stream = new MemoryStream();
            using (var writer = new System.Text.Json.Utf8JsonWriter(stream))
            {
                writer.WriteStartArray();
                lock (task.ChunkLock)
                {
                    foreach (var c in task.Chunks)
                    {
                        writer.WriteStartObject();
                        writer.WriteNumber("s", c.Start);
                        writer.WriteNumber("e", c.End);
                        writer.WriteNumber("d", c.Downloaded);
                        writer.WriteEndObject();
                    }
                }
                writer.WriteEndArray();
            }
            File.WriteAllText(task.StatePath, Encoding.UTF8.GetString(stream.ToArray()));
        }
        catch { }
    }

    private static List<ChunkState> LoadChunks(string file)
    {
        try
        {
            var arr = System.Text.Json.JsonDocument.Parse(File.ReadAllText(file)).RootElement;
            var list = new List<ChunkState>();
            foreach (var o in arr.EnumerateArray())
            {
                var s = o.GetProperty("s").GetInt64();
                var e = o.GetProperty("e").GetInt64();
                var d = o.TryGetProperty("d", out var dEl) ? dEl.GetInt64() : 0;
                list.Add(new ChunkState(s, e, d));
            }
            return list;
        }
        catch
        {
            return new List<ChunkState>();
        }
    }

    /// <summary>وضعیت در حال اجرای یک دانلود</summary>
    private sealed class RunningTask
    {
        public readonly object Mutex = new();
        public readonly object ChunkLock = new();
        public readonly object WriteLock = new();
        public long GenerationField;
        public volatile bool Paused;
        public volatile bool Canceled;
        public long DownloadedField;
        public readonly List<ChunkState> Chunks = new();
        private CancellationTokenSource? _cts;
        private readonly object _errorLock = new();

        public readonly string Id;
        public readonly string Url;
        public readonly string PartPath;
        public readonly string StatePath;
        public readonly string FileName;
        public long TotalBytes;
        public bool AcceptRanges;
        public string? Error { get; private set; }

        public RunningTask(string id, string url, string partPath, string statePath, string fileName)
        {
            Id = id;
            Url = url;
            PartPath = partPath;
            StatePath = statePath;
            FileName = fileName;
        }

        public void SetError(string message)
        {
            lock (_errorLock) { Error ??= message; }
        }

        public void SetCts(CancellationTokenSource cts) { _cts = cts; }
        public void CancelCurrent() { try { _cts?.Cancel(); } catch { } }
    }
}
