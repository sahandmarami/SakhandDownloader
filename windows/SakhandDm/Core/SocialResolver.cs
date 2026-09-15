using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Web;

namespace SakhandDm.Core;

/// <summary>خطای قابل نمایش به کاربر</summary>
public class SocialException : Exception
{
    public SocialException(string message) : base(message) { }
}

/// <summary>
/// استخراج لینک مستقیم رسانه از شبکه‌های اجتماعی
/// - پینترست: مستقیم و بدون واسطه (API رسمی صفحه + تحلیل HTML)
/// - یوتیوب و اینستاگرام: سرویس کوبالت با چند سرور پشتیبان به‌صورت همزمان
/// </summary>
public static class SocialResolver
{
    // ------------------------------------------------------------
    // تنظیمات سرور شخصی (اختیاری) — اگر سرور خودت را راه انداختی پر کن
    // ------------------------------------------------------------
    private const string CustomEndpoint = "";
    private const string CustomApiKey = "";

    private static readonly string[] CobaltEndpoints =
    {
        "https://dwnld.nichind.dev/",
        "https://cobalt-api.kwiatekmiki.com/",
        "https://nyc1.coapi.ggtyler.dev/",
        "https://cobalt.255.one/",
        "https://api.dl.ixhby.dev/"
    };

    private const string MobileUa =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36";

    private const string DesktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    /// <summary>یوزراجنت عمومی دانلود — برنامه دسکتاپ است</summary>
    public const string UserAgent = DesktopUa;

    private static readonly HttpClient PageClient = MakeClient(TimeSpan.FromSeconds(10), TimeSpan.FromSeconds(30));
    private static readonly HttpClient CobaltClient = MakeClient(TimeSpan.FromSeconds(7), TimeSpan.FromSeconds(20));

    private static HttpClient MakeClient(TimeSpan connect, TimeSpan call)
    {
        var handler = new SocketsHttpHandler
        {
            ConnectTimeout = connect,
            AllowAutoRedirect = true,
            UseCookies = false,
            AutomaticDecompression = System.Net.DecompressionMethods.All
        };
        return new HttpClient(handler) { Timeout = call };
    }

    public static async Task<ResolvedMedia> ResolveAsync(
        string link, string quality, bool audioOnly, SocialPlatform platform)
    {
        return platform == SocialPlatform.Pinterest
            ? await ResolvePinterestAsync(link)
            : await ResolveViaCobaltAsync(link, quality, audioOnly, platform);
    }

    // ============================================================
    // پینترست — دانلود مستقیم بدون واسطه
    // ============================================================

    private static async Task<ResolvedMedia> ResolvePinterestAsync(string link)
    {
        var (html, finalUrl) = await FetchPageAsync(link);
        var pinId = Regex.Match(finalUrl, @"\d{8,}").Value;
        if (string.IsNullOrEmpty(pinId))
        {
            var head = html.Length > 20000 ? html[..20000] : html;
            pinId = Regex.Match(head, @"\d{8,}").Value;
        }
        var ts = DateTimeOffset.Now.ToUnixTimeMilliseconds();
        var pinPart = string.IsNullOrEmpty(pinId) ? ts.ToString() : pinId;

        // ۱) API رسمی پین — دقیق‌ترین منبع برای تشخیص ویدیو
        ResolvedMedia? pinImage = null;
        if (!string.IsNullOrEmpty(pinId))
        {
            var media = await TryPinResourceAsync(pinId);
            if (media != null && media.Mime == "video/mp4") return media;
            pinImage = media;
        }

        // ۲) ویدیو داخل خود صفحه (v1.pinimg.com .mp4)
        var htmlVid = HtmlVideo(html);
        if (htmlVid != null) return new ResolvedMedia(htmlVid, $"pinterest_{pinPart}.mp4", "video/mp4");

        // ۳) متاتگ og:video
        var ogV = OgVideo(html);
        if (ogV != null) return new ResolvedMedia(ogV, $"pinterest_{pinPart}.mp4", "video/mp4");

        // ۴) نسخه موبایل صفحه
        try
        {
            var mobile = await FetchPageAsync(link, MobileUa);
            var mv = HtmlVideo(mobile.Html);
            if (mv != null) return new ResolvedMedia(mv, $"pinterest_{pinPart}.mp4", "video/mp4");
            var mog = OgVideo(mobile.Html);
            if (mog != null) return new ResolvedMedia(mog, $"pinterest_{pinPart}.mp4", "video/mp4");
        }
        catch { /* نسخه موبایل اختیاری است */ }

        // ۵) عکس با بالاترین کیفیت
        if (pinImage != null) return pinImage;
        var img = OgImage(html);
        if (img != null) return new ResolvedMedia(img, $"pinterest_{pinPart}.jpg", "image/jpeg");

        throw new SocialException("نتوانستم رسانه این پین را پیدا کنم — مطمئن شو لینک یک پین عمومی است");
    }

    private static async Task<ResolvedMedia?> TryPinResourceAsync(string pinId)
    {
        try
        {
            var payload = "{\"options\":{\"id\":\"" + pinId + "\",\"field_set_key\":\"detailed\"," +
                          "\"fetch_visual_search_objects\":false},\"context\":{}}";
            var url = "https://www.pinterest.com/resource/PinResource/get/" +
                      "?source_url=" + HttpUtility.UrlEncode("/pin/" + pinId + "/") +
                      "&data=" + HttpUtility.UrlEncode(payload);

            using var req = new System.Net.Http.HttpRequestMessage(System.Net.Http.HttpMethod.Get, url);
            req.Headers.TryAddWithoutValidation("User-Agent", MobileUa);
            req.Headers.TryAddWithoutValidation("Accept", "application/json");
            req.Headers.TryAddWithoutValidation("X-Requested-With", "XMLHttpRequest");
            req.Headers.TryAddWithoutValidation("X-Pinterest-Appstate", "active");

            using var resp = await PageClient.SendAsync(req);
            if (!resp.IsSuccessStatusCode) return null;
            var text = await resp.Content.ReadAsStringAsync();
            var root = System.Text.Json.JsonDocument.Parse(text).RootElement;

            if (!root.TryGetProperty("resource_response", out var rr)) return null;
            if (!rr.TryGetProperty("data", out var data)) return null;

            // اول ویدیو — تک‌ویدیو یا Idea Pin
            if (data.TryGetProperty("videos", out var videos) &&
                videos.TryGetProperty("video_list", out var vl))
            {
                var best = BestMp4(vl);
                if (best != null) return new ResolvedMedia(best, $"pinterest_{pinId}.mp4", "video/mp4");
            }

            if (data.TryGetProperty("story_pin_data", out var story) &&
                story.TryGetProperty("pages", out var pages) &&
                pages.ValueKind == System.Text.Json.JsonValueKind.Array)
            {
                foreach (var page in pages.EnumerateArray())
                {
                    if (!page.TryGetProperty("blocks", out var blocks)) continue;
                    foreach (var block in blocks.EnumerateArray())
                    {
                        if (block.TryGetProperty("video", out var video) &&
                            video.TryGetProperty("video_list", out var vlist))
                        {
                            var best = BestMp4(vlist);
                            if (best != null) return new ResolvedMedia(best, $"pinterest_{pinId}.mp4", "video/mp4");
                        }
                    }
                }
            }

            // عکس با کیفیت اصلی (orig)
            if (data.TryGetProperty("images", out var images) &&
                images.TryGetProperty("orig", out var orig) &&
                orig.TryGetProperty("url", out var u))
            {
                var origUrl = u.GetString();
                if (!string.IsNullOrWhiteSpace(origUrl))
                    return new ResolvedMedia(origUrl, $"pinterest_{pinId}.jpg", "image/jpeg");
            }

            return null;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>بهترین فایل mp4 مستقیم از بین نسخه‌ها (HLS رد می‌شود)</summary>
    private static string? BestMp4(System.Text.Json.JsonElement videoList)
    {
        if (videoList.ValueKind != System.Text.Json.JsonValueKind.Object) return null;
        int bestScore = int.MinValue;
        string? bestUrl = null;
        foreach (var prop in videoList.EnumerateObject())
        {
            if (prop.Value.ValueKind != System.Text.Json.JsonValueKind.Object) continue;
            var entry = prop.Value;
            if (!entry.TryGetProperty("url", out var urlEl)) continue;
            var url = urlEl.GetString();
            if (string.IsNullOrWhiteSpace(url)) continue;
            var low = url.ToLowerInvariant();
            if (low.Contains(".m3u8") || !low.Contains(".mp4")) continue;
            int width = entry.TryGetProperty("width", out var wEl) && wEl.TryGetInt32(out var wv) ? wv : 0;
            var key = prop.Name.ToUpperInvariant();
            int score = (key.Contains("1080") ? 110 : key.Contains("720") ? 100 : 50) + width / 10000;
            if (bestUrl == null || score > bestScore) { bestScore = score; bestUrl = url; }
        }
        return bestUrl;
    }

    private static string? HtmlVideo(string html)
    {
        var unescaped = html
            .Replace("\\u002F", "/")
            .Replace("\\u002f", "/")
            .Replace("\\/", "/");
        var candidates = Regex.Matches(unescaped, @"https://v1?\.pinimg\.com/videos/[^""'<>\s\\]+\.mp4")
            .Select(m => m.Value).Distinct().ToList();
        var pool = candidates;
        if (pool.Count == 0)
            pool = Regex.Matches(unescaped, @"https://[^""'<>\s\\]+\.mp4")
                .Select(m => m.Value).Distinct().ToList();
        if (pool.Count == 0) return null;
        return pool.Max(url =>
        {
            var low = url.ToLowerInvariant();
            int score = low.Contains("720p") ? 3 : low.Contains("expmp4") ? 2 : 1;
            return (score, url);
        }).url;
    }

    private static string? OgVideo(string html)
    {
        foreach (var prop in new[] { "og:video", "og:video:secure_url", "og:video:url" })
        {
            var url = ExtractMeta(html, prop);
            if (url == null) continue;
            var low = url.ToLowerInvariant();
            if (low.Contains("/videos/") || low.Contains(".mp4"))
                return url.Replace("&amp;", "&");
        }
        return null;
    }

    private static string? OgImage(string html)
    {
        foreach (var prop in new[] { "og:image", "og:image:secure_url", "twitter:image" })
        {
            var url = ExtractMeta(html, prop);
            if (url != null) return url.Replace("&amp;", "&");
        }
        return null;
    }

    private static string? ExtractMeta(string html, string property)
    {
        var patterns = new[]
        {
            new Regex($@"<meta[^>]+property=[""']{property}[""'][^>]*content=[""']([^""']+)[""']", RegexOptions.IgnoreCase),
            new Regex($@"<meta[^>]+content=[""']([^""']+)[""'][^>]*property=[""']{property}[""']", RegexOptions.IgnoreCase)
        };
        foreach (var re in patterns)
        {
            var m = re.Match(html);
            if (m.Success) return m.Groups[1].Value;
        }
        return null;
    }

    // ============================================================
    // یوتیوب و اینستاگرام — سرویس کوبالت
    // ============================================================

    private sealed class Attempt
    {
        public ResolvedMedia? Media;
        public bool LocalProcessing;
        public string? Reason;
    }

    private sealed class RaceResult
    {
        private readonly object _lock = new();
        public Attempt? Winner;
        public readonly List<string> LocalEndpoints = new();
        public string? LastReason;

        public void SetWinnerIfAbsent(Attempt a)
        {
            lock (_lock) { Winner ??= a; }
        }

        public void RecordLocal(string ep)
        {
            lock (_lock) { LocalEndpoints.Add(ep); }
        }
    }

    private static async Task<ResolvedMedia> ResolveViaCobaltAsync(
        string link, string quality, bool audioOnly, SocialPlatform platform)
    {
        var endpoints = new List<string>();
        if (!string.IsNullOrWhiteSpace(CustomEndpoint)) endpoints.Add(CustomEndpoint);
        endpoints.AddRange(CobaltEndpoints);
        endpoints = endpoints.Distinct().ToList();

        var q = audioOnly ? "1080" : quality;

        // مرحله ۱: همه سرورها همزمان با کیفیت اصلی
        var race = new RaceResult();
        var tasks = endpoints.Select(async ep =>
        {
            Attempt attempt;
            try { attempt = await CallCobaltAsync(ep, link, q, audioOnly, platform); }
            catch { attempt = new Attempt { Reason = "ارتباط با سرور برقرار نشد" }; }

            if (attempt.Media != null) race.SetWinnerIfAbsent(attempt);
            else if (attempt.LocalProcessing) race.RecordLocal(ep);
            else if (attempt.Reason != null) race.LastReason = attempt.Reason;
        }).ToArray();
        await Task.WhenAll(tasks);

        // مرحله ۲: اگر فقط «پردازش محلی» جواب داد، با 720 تک‌فایله امتحان کن
        var winner = race.Winner;
        if (winner == null)
        {
            foreach (var ep in race.LocalEndpoints)
            {
                Attempt attempt;
                try { attempt = await CallCobaltAsync(ep, link, "720", audioOnly, platform); }
                catch { attempt = new Attempt { Reason = "ارتباط با سرور برقرار نشد" }; }
                if (attempt.Media != null) { winner = attempt; break; }
            }
        }

        if (winner?.Media != null) return winner.Media;

        var hint = race.LastReason != null && race.LastReason.Contains("کلید دسترسی")
            ? " سرورهای عمومی محدود شده‌اند — می‌توانی طبق README یک سرور شخصی بسازی."
            : "";
        throw new SocialException(
            $"دانلود از {platform.Label()} ناموفق بود — {race.LastReason ?? "هیچ سروری پاسخ نداد"}{hint}");
    }

    private static async Task<Attempt> CallCobaltAsync(
        string endpoint, string link, string quality, bool audioOnly, SocialPlatform platform)
    {
        var body = System.Text.Json.JsonSerializer.Serialize(new Dictionary<string, string>
        {
            ["url"] = link,
            ["videoQuality"] = quality, // auto / 1080 / 720 / 480
            ["downloadMode"] = audioOnly ? "audio" : "auto",
            ["filenameStyle"] = "pretty",
            ["youtubeVideoCodec"] = "h264" // حتماً mp4 تک‌فایله
        });

        using var req = new System.Net.Http.HttpRequestMessage(System.Net.Http.HttpMethod.Post, endpoint);
        req.Content = new StringContent(body, System.Text.Encoding.UTF8, "application/json");
        req.Headers.TryAddWithoutValidation("Accept", "application/json");
        req.Headers.TryAddWithoutValidation("User-Agent", UserAgent);
        if (!string.IsNullOrWhiteSpace(CustomApiKey) && endpoint == CustomEndpoint)
            req.Headers.TryAddWithoutValidation("Authorization", $"Api-Key {CustomApiKey}");

        try
        {
            using var resp = await CobaltClient.SendAsync(req);
            var text = await resp.Content.ReadAsStringAsync();
            if (string.IsNullOrWhiteSpace(text)) return new Attempt { Reason = "پاسخ خالی از سرور" };

            System.Text.Json.JsonElement json;
            try { json = System.Text.Json.JsonDocument.Parse(text).RootElement; }
            catch { return new Attempt { Reason = $"پاسخ نامعتبر از سرور (کد {(int)resp.StatusCode})" }; }

            var status = json.TryGetProperty("status", out var stEl) ? stEl.GetString() : null;
            switch (status)
            {
                case "tunnel":
                case "redirect":
                case "stream":
                {
                    var url = json.GetProperty("url").GetString() ?? "";
                    var filename = json.TryGetProperty("filename", out var fnEl) ? fnEl.GetString() : null;
                    if (string.IsNullOrWhiteSpace(filename)) filename = DefaultName(platform, audioOnly);
                    var mime = json.TryGetProperty("mime", out var mimeEl) ? mimeEl.GetString() : null;
                    if (string.IsNullOrWhiteSpace(mime)) mime = audioOnly ? "audio/mp4" : "video/mp4";
                    return new Attempt { Media = new ResolvedMedia(url, filename, mime) };
                }
                case "local-processing":
                    return new Attempt { LocalProcessing = true };
                case "error":
                    return new Attempt { Reason = ErrorFa(json) };
                default:
                    return new Attempt { Reason = $"وضعیت ناشناخته از سرور ({status})" };
            }
        }
        catch
        {
            return new Attempt { Reason = "ارتباط با سرور برقرار نشد" };
        }
    }

    private static string ErrorFa(System.Text.Json.JsonElement json)
    {
        string? code = null;
        if (json.TryGetProperty("error", out var errEl))
        {
            if (errEl.ValueKind == System.Text.Json.JsonValueKind.Object &&
                errEl.TryGetProperty("code", out var cEl)) code = cEl.GetString();
            else if (errEl.ValueKind == System.Text.Json.JsonValueKind.String) code = errEl.GetString();
        }
        code ??= "";
        return code switch
        {
            "api.auth.key.missing" or "api.auth.jwt.missing" or "api.auth.jwt.invalid"
                => "این سرور به کلید دسترسی نیاز دارد",
            "link.unsupported" => "این لینک توسط سرویس استخراج پشتیبانی نمی‌شود",
            "link.invalid" => "لینک واردشده معتبر نیست",
            "content.video.unavailable" => "ویدیو در دسترس نیست (خصوصی یا حذف‌شده)",
            "content.video.age" => "ویدیوهای با محدودیت سنی پشتیبانی نمی‌شوند",
            "content.post.private" => "این پست خصوصی است و قابل دانلود نیست",
            "content.instagram.auth_required" => "اینستاگرام برای این لینک احراز هویت می‌خواهد — لینک دیگری امتحان کن",
            "rate_exceeded" => "تعداد درخواست‌ها زیاد است — چند لحظه بعد دوباره امتحان کن",
            "" => "خطای ناشناخته",
            _ => $"خطا: {code}"
        };
    }

    private static string DefaultName(SocialPlatform platform, bool audioOnly)
    {
        var ts = DateTimeOffset.Now.ToUnixTimeMilliseconds();
        var p = platform switch
        {
            SocialPlatform.Youtube => "youtube",
            SocialPlatform.Instagram => "instagram",
            SocialPlatform.Pinterest => "pinterest",
            _ => "media"
        };
        return $"{p}_{ts}.{(audioOnly ? "m4a" : "mp4")}";
    }

    // ------------------------------------------------------------

    private static async Task<(string Html, string FinalUrl)> FetchPageAsync(string url, string ua = DesktopUa)
    {
        using var req = new System.Net.Http.HttpRequestMessage(System.Net.Http.HttpMethod.Get, url);
        req.Headers.TryAddWithoutValidation("User-Agent", ua);
        req.Headers.TryAddWithoutValidation("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
        using var resp = await PageClient.SendAsync(req);
        if (!resp.IsSuccessStatusCode)
            throw new SocialException($"دسترسی به صفحه ممکن نشد (کد {(int)resp.StatusCode})");
        var html = await resp.Content.ReadAsStringAsync();
        if (string.IsNullOrEmpty(html)) throw new SocialException("محتوای صفحه خالی بود");
        return (html, resp.RequestMessage?.RequestUri?.ToString() ?? url);
    }
}
