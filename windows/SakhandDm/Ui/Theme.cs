using System;
using System.IO;
using System.Text.Json;
using Avalonia;
using Avalonia.Media;

namespace SakhandDm.Ui;

/// <summary>رنگ‌های وابسته به تم — پورت مستقیم پالت اندروید</summary>
public sealed class AppColors
{
    public IBrush Background = null!;
    public IBrush Surface = null!;
    public IBrush Card = null!;
    public IBrush CardBorder = null!;
    public IBrush Track = null!;
    public IBrush TextPrimary = null!;
    public IBrush TextSecondary = null!;
    public IBrush AccentGreen = null!;
    public IBrush AccentRed = null!;
    public IBrush AccentAmber = null!;
    public IBrush Purple = null!;
    public IBrush White = null!;
    public bool IsDark;

    public IBrush BrandGradient { get; } = MakeGradient(Color.Parse("#8B5CF6"), Color.Parse("#3B82F6"));
    public IBrush HeroGradient { get; } = MakeGradient(
        Color.Parse("#8B5CF6"), Color.Parse("#3B82F6"), Color.Parse("#EB3B82F6"));
    public IBrush DisabledFill { get; } = new SolidColorBrush(Color.Parse("#334155"));

    /// <summary>آبی وضعیت دانلود — همان Blue اندروید</summary>
    public IBrush Blue { get; } = SolidColorBrush.Parse("#3B82F6");

    /// <summary>پس‌زمینه چیپ انتخاب‌شده — بنفش ۲۵٪ (مثل FilterChip اندروید)</summary>
    public IBrush ChipSelectedBg { get; } = SolidColorBrush.Parse("#408B5CF6");

    /// <summary>متن چیپ انتخاب‌شده — در تم روشن بنفش تیره، در تیره سفید (مثل اندروید)</summary>
    public IBrush ChipSelectedFg => IsDark
        ? White
        : SolidColorBrush.Parse("#2B1B57");

    /// <summary>قرص پشت آیکون تب انتخاب‌شده — بنafsh ۱۶٪ (NavigationBarIndicator)</summary>
    public IBrush NavPill { get; } = SolidColorBrush.Parse("#298B5CF6");

    /// <summary>پس‌زمineh اسnackBar — تیره در هر دو تم (Inverse Surface)</summary>
    public IBrush SnackBg { get; } = SolidColorBrush.Parse("#F0313033");

    /// <summary>متن خطا در اسنک‌بار</summary>
    public IBrush SnackErr { get; } = SolidColorBrush.Parse("#FFB4AB");

    /// <summary>حلقه گرادیانی دور آیکون در صفحه درباره (مثل اندروید)</summary>
    public IBrush RingGradient { get; } = MakeGradient(
        Color.Parse("#408B5CF6"), Color.Parse("#403B82F6"));

    public static IBrush MakeGradient(params Color[] colors)
    {
        var brush = new LinearGradientBrush
        {
            StartPoint = new RelativePoint(0, 0, RelativeUnit.Relative),
            EndPoint = new RelativePoint(1, 1, RelativeUnit.Relative)
        };
        var step = colors.Length == 1 ? 1.0 : 1.0 / (colors.Length - 1);
        for (var i = 0; i < colors.Length; i++)
            brush.GradientStops.Add(new GradientStop(colors[i], i * step));
        return brush;
    }
}

/// <summary>کنترل تم — روشن یا تیره، با ذخیره انتخاب کاربر</summary>
public static class AppTheme
{
    private const string FileSettings = "settings.json";
    private static AppColors? _dark;
    private static AppColors? _light;

    public static bool IsLight { get; private set; }

    public static event Action? Changed;

    public static void Load(string appData)
    {
        try
        {
            var path = Path.Combine(appData, FileSettings);
            if (File.Exists(path))
            {
                var doc = JsonDocument.Parse(File.ReadAllText(path));
                IsLight = doc.RootElement.TryGetProperty("light", out var el) &&
                          el.ValueKind == JsonValueKind.True;
            }
        }
        catch { IsLight = false; }
    }

    public static void SetLight(string appData, bool light)
    {
        IsLight = light;
        try
        {
            Directory.CreateDirectory(appData);
            File.WriteAllText(Path.Combine(appData, FileSettings),
                JsonSerializer.Serialize(new { light }));
        }
        catch { }
        Changed?.Invoke();
    }

    public static AppColors Colors => IsLight ? (_light ??= Light()) : (_dark ??= Dark());

    public static AppColors Dark() => new()
    {
        Background = SolidColorBrush.Parse("#0A0E1A"),
        Surface = SolidColorBrush.Parse("#111827"),
        Card = SolidColorBrush.Parse("#16203A"),
        CardBorder = SolidColorBrush.Parse("#243050"),
        Track = SolidColorBrush.Parse("#1E293B"),
        TextPrimary = SolidColorBrush.Parse("#F1F5F9"),
        TextSecondary = SolidColorBrush.Parse("#94A3B8"),
        AccentGreen = SolidColorBrush.Parse("#34D399"),
        AccentRed = SolidColorBrush.Parse("#F87171"),
        AccentAmber = SolidColorBrush.Parse("#FBBF24"),
        Purple = SolidColorBrush.Parse("#8B5CF6"),
        White = Brushes.White,
        IsDark = true
    };

    public static AppColors Light() => new()
    {
        Background = SolidColorBrush.Parse("#F5F6FC"),
        Surface = SolidColorBrush.Parse("#FFFFFF"),
        Card = SolidColorBrush.Parse("#FFFFFF"),
        CardBorder = SolidColorBrush.Parse("#E3E5F2"),
        Track = SolidColorBrush.Parse("#EAECF6"),
        TextPrimary = SolidColorBrush.Parse("#181C33"),
        TextSecondary = SolidColorBrush.Parse("#676D8C"),
        AccentGreen = SolidColorBrush.Parse("#059669"),
        AccentRed = SolidColorBrush.Parse("#DC2626"),
        AccentAmber = SolidColorBrush.Parse("#D97706"),
        Purple = SolidColorBrush.Parse("#6D3AE8"),
        White = Brushes.White,
        IsDark = false
    };
}
