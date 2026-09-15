namespace SakhandDm.Core;

/// <summary>تشخیص خودکار پلتفرم از روی لینک</summary>
public static class SocialDetector
{
    public static SocialPlatform? Detect(string? url)
    {
        if (string.IsNullOrWhiteSpace(url)) return null;
        var u = url.Trim().ToLowerInvariant();
        if (u.Contains("youtube.com") || u.Contains("youtu.be") || u.Contains("youtube-nocookie.com"))
            return SocialPlatform.Youtube;
        if (u.Contains("instagram.com") || u.Contains("instagr.am"))
            return SocialPlatform.Instagram;
        if (u.Contains("pinterest.com") || u.Contains("pin.it"))
            return SocialPlatform.Pinterest;
        return null;
    }
}
