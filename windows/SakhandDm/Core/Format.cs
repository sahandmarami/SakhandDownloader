using System.Collections.Generic;

namespace SakhandDm.Core;

/// <summary>قالب‌بندی اعداد و حجم به سبک فارسی</summary>
public static class Fmt
{
    private static readonly Dictionary<char, char> FaDigits = new()
    {
        ['0'] = '۰', ['1'] = '۱', ['2'] = '۲', ['3'] = '۳', ['4'] = '۴',
        ['5'] = '۵', ['6'] = '۶', ['7'] = '۷', ['8'] = '۸', ['9'] = '۹',
        ['.'] = '٫', [','] = '٬'
    };

    public static string ToFa(long value)
    {
        var s = value.ToString();
        var sb = new System.Text.StringBuilder(s.Length);
        foreach (var c in s) sb.Append(FaDigits.TryGetValue(c, out var f) ? f : c);
        return sb.ToString();
    }

    public static string ToFaDigits(this string s)
    {
        var sb = new System.Text.StringBuilder(s.Length);
        foreach (var c in s) sb.Append(FaDigits.TryGetValue(c, out var f) ? f : c);
        return sb.ToString();
    }

    public static string FormatBytes(long bytes)
    {
        if (bytes < 0) return "نامشخص";
        if (bytes == 0) return "۰ بایت";
        double b = bytes;
        string s = b >= 1024d * 1024 * 1024
            ? string.Format(System.Globalization.CultureInfo.InvariantCulture, "{0:F2} گیگابایت", b / (1024d * 1024 * 1024))
            : b >= 1024d * 1024
                ? string.Format(System.Globalization.CultureInfo.InvariantCulture, "{0:F1} مگابایت", b / (1024d * 1024))
                : b >= 1024
                    ? string.Format(System.Globalization.CultureInfo.InvariantCulture, "{0:F1} کیلوبایت", b / 1024)
                    : $"{bytes} بایت";
        return s.ToFaDigits();
    }

    public static string FormatSpeed(long bps) => bps <= 0 ? "—" : FormatBytes(bps) + " بر ثانیه";

    public static string EtaText(long remainingBytes, long bps)
    {
        if (bps <= 0 || remainingBytes <= 0) return "—";
        long sec = remainingBytes / bps;
        if (sec < 60) return $"حدود {ToFa(sec)} ثانیه";
        if (sec < 3600) return $"حدود {ToFa(sec / 60)} دقیقه";
        return $"حدود {ToFa(sec / 3600)} ساعت";
    }
}
