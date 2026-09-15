using System;

namespace SakhandDm.Core;

/// <summary>وضعیت هر دانلود در چرخه حیات</summary>
public enum DownloadStatus
{
    Queued,
    Connecting,
    Downloading,
    Paused,
    Completed,
    Failed
}

/// <summary>پلتفرم‌های اجتماعی پشتیبانی‌شده</summary>
public enum SocialPlatform
{
    Youtube,
    Instagram,
    Pinterest
}

public static class SocialPlatformExtensions
{
    public static string Label(this SocialPlatform p) => p switch
    {
        SocialPlatform.Youtube => "یوتیوب",
        SocialPlatform.Instagram => "اینستاگرام",
        SocialPlatform.Pinterest => "پینترست",
        _ => ""
    };
}

/// <summary>مدل یک دانلود</summary>
public sealed class DownloadItem
{
    public string Id { get; init; } = "";
    public string Url { get; init; } = "";
    public string FileName { get; set; } = "";
    public long TotalBytes { get; set; }
    public long DownloadedBytes { get; set; }
    public long SpeedBps { get; set; }
    public DownloadStatus Status { get; set; } = DownloadStatus.Queued;
    public string? ErrorMessage { get; set; }
    public long CreatedAt { get; init; } = DateTimeOffset.Now.ToUnixTimeMilliseconds();
    public bool IsSocial { get; init; }
    public string? Platform { get; init; }
    public string? Mime { get; set; }
    public string? SavedPath { get; set; }

    public bool IsActive =>
        Status is DownloadStatus.Queued or DownloadStatus.Connecting or DownloadStatus.Downloading;
}

/// <summary>وضعیت یک بخش (chunk) از فایل برای دانلود چندتردی</summary>
public sealed class ChunkState
{
    public long Start { get; }
    public long End { get; }
    public long Downloaded { get; set; }
    public bool Done { get; set; }

    public ChunkState(long start, long end, long downloaded = 0)
    {
        Start = start;
        End = end;
        Downloaded = downloaded;
        Done = downloaded >= Size && Size > 0;
    }

    public long Size => End >= Start ? End - Start + 1 : -1;
}

/// <summary>نتیجه استخراج رسانه اجتماعی</summary>
public sealed record ResolvedMedia(string Url, string FileName, string Mime);
