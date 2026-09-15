using System;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using SakhandDm.Core;

namespace SakhandDm.Ui;

/// <summary>
/// کارت دانلود — پورت مستقیم DownloadCard.kt اندروید + دکمههای مخصوص ویندوز
/// (باز کردن پوشه و تغییر نام). هم در صفحه اصلی (compact) و هم در صفحه دانلودها.
/// </summary>
public sealed class DownloadRow : Border
{
    public string Id { get; }
    private readonly DownloadEngine _engine;
    private readonly Action<string, bool> _notify;
    private readonly Window _owner;
    private readonly bool _compact;

    private readonly Border _iconCircle = new();
    private PathIcon _icon = null!;
    private readonly TextBlock _fileName = new();
    private readonly TextBlock _subtitle = new();
    private readonly TextBlock _pct = new();
    private readonly TextBlock _err = new();
    private readonly Border _track = new();
    private readonly ColumnDefinition _fillCol = new(0, GridUnitType.Star);
    private readonly ColumnDefinition _restCol = new(1, GridUnitType.Star);
    private readonly StackPanel _buttons = new();
    private int _lastStatus = -1;

    public DownloadRow(DownloadEngine engine, DownloadItem item, Action<string, bool> notify,
        Window owner, bool compact = false)
    {
        _engine = engine;
        _notify = notify;
        _owner = owner;
        _compact = compact;
        Id = item.Id;

        CornerRadius = new CornerRadius(20);
        Padding = new Thickness(16, 15, 16, 15);
        BuildStatic();
        Update(item);
    }

    private void BuildStatic()
    {
        var colors = AppTheme.Colors;
        Background = colors.Card;
        BorderBrush = colors.CardBorder;
        BorderThickness = new Thickness(1);

        var grid = new Grid
        {
            RowDefinitions =
            {
                new RowDefinition(GridLength.Auto), // سطر اصلی: آیکون + نام
                new RowDefinition(GridLength.Auto), // نوار پیشرفت
                new RowDefinition(GridLength.Auto), // درصد
                new RowDefinition(GridLength.Auto), // خطا
                new RowDefinition(GridLength.Auto)  // دکمهها
            }
        };

        // ---- سطر ۰: دایره وضعیت + نام و زیرنویس (مثل کارت اندروید) ----
        var top = new Grid
        {
            ColumnDefinitions =
            {
                new ColumnDefinition(GridLength.Auto),
                new ColumnDefinition(1, GridUnitType.Star)
            }
        };
        _iconCircle.Width = 46;
        _iconCircle.Height = 46;
        _iconCircle.CornerRadius = new CornerRadius(23);
        _iconCircle.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(_iconCircle, 0);
        top.Children.Add(_iconCircle);

        var textCol = new StackPanel
        {
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(12, 0, 12, 0)
        };
        _fileName.FontSize = 14;
        _fileName.FontWeight = FontWeight.SemiBold;
        _fileName.TextTrimming = TextTrimming.CharacterEllipsis;
        _fileName.Foreground = colors.TextPrimary;
        _fileName.Margin = new Thickness(0, 0, 0, 3);
        textCol.Children.Add(_fileName);
        _subtitle.FontSize = 12;
        _subtitle.TextTrimming = TextTrimming.CharacterEllipsis;
        _subtitle.Foreground = colors.TextSecondary;
        textCol.Children.Add(_subtitle);
        Grid.SetColumn(textCol, 1);
        top.Children.Add(textCol);
        Grid.SetRow(top, 0);
        grid.Children.Add(top);

        // ---- سطر ۱: نوار پیشرفت ۸ پیکسلی ----
        _fillGrid();
        _track.Child = _fillGridCache;
        _track.Height = 8;
        _track.CornerRadius = new CornerRadius(4);
        _track.ClipToBounds = true;
        _track.Background = colors.Track;
        _track.Margin = new Thickness(0, 13, 0, 0);
        Grid.SetRow(_track, 1);
        grid.Children.Add(_track);

        // ---- سطر ۲: درصد (فقط دانلود کامل صفحه دانلودها — مثل اندروید) ----
        _pct.FontSize = 12;
        _pct.FontWeight = FontWeight.SemiBold;
        _pct.Margin = new Thickness(1, 7, 1, 0);
        _pct.HorizontalAlignment = HorizontalAlignment.Stretch;
        _pct.TextAlignment = TextAlignment.Right;
        _pct.IsVisible = false;
        Grid.SetRow(_pct, 2);
        grid.Children.Add(_pct);

        // ---- سطر ۳: پیام خطا ----
        _err.FontSize = 12;
        _err.Foreground = AppTheme.Colors.AccentRed;
        _err.TextWrapping = TextWrapping.Wrap;
        _err.Margin = new Thickness(1, 7, 1, 0);
        _err.IsVisible = false;
        Grid.SetRow(_err, 3);
        grid.Children.Add(_err);

        // ---- سطر ۴: دکمهها ----
        _buttons.Orientation = Orientation.Horizontal;
        _buttons.Margin = new Thickness(0, 12, 0, 0);
        _buttons.IsVisible = false;
        Grid.SetRow(_buttons, 4);
        grid.Children.Add(_buttons);

        Child = grid;
    }

    private Grid _fillGridCache = null!;
    private void _fillGrid()
    {
        var g = new Grid();
        g.ColumnDefinitions.Add(_fillCol);
        g.ColumnDefinitions.Add(_restCol);
        var fill = new Border
        {
            Background = AppTheme.Colors.Blue,
            CornerRadius = new CornerRadius(4),
            HorizontalAlignment = HorizontalAlignment.Stretch
        };
        Grid.SetColumn(fill, 0);
        g.Children.Add(fill);
        _fillGridCache = g;
    }

    public void Update(DownloadItem item)
    {
        var colors = AppTheme.Colors;
        var color = StatusColor(item.Status, colors);
        var iconPath = StatusIcon(item.Status);

        // دایره وضعیت
        _iconCircle.Background = Tint(color, 0.15);
        if (_iconCircle.Child is not PathIcon pi || _lastIconPath != iconPath || _lastIconColor != color)
        {
            _icon = Ico.Make(iconPath, 23, color);
            _iconCircle.Child = _icon;
            _lastIconPath = iconPath;
            _lastIconColor = color;
        }
        else
        {
            _icon.Foreground = color;
        }

        _fileName.Text = item.FileName;

        // زیرنویس — دقیقاً مثل DownloadCard.kt
        var subtitle = item.Status switch
        {
            DownloadStatus.Connecting => "در حال برقراری اتصال…",
            DownloadStatus.Queued => "در صف دانلود",
            DownloadStatus.Downloading => item.TotalBytes > 0
                ? $"{Fmt.FormatBytes(item.DownloadedBytes)} از {Fmt.FormatBytes(item.TotalBytes)} • {Fmt.FormatSpeed(item.SpeedBps)}"
                : $"{Fmt.FormatBytes(item.DownloadedBytes)} • {Fmt.FormatSpeed(item.SpeedBps)}",
            DownloadStatus.Paused => $"متوقف شد • {Fmt.FormatBytes(item.DownloadedBytes)} دریافت شده",
            DownloadStatus.Completed => $"تکمیل شد • {Fmt.FormatBytes(item.TotalBytes)}",
            DownloadStatus.Failed => "دانلود ناموفق بود",
            _ => ""
        };
        if (item.IsSocial && !string.IsNullOrEmpty(item.Platform))
            subtitle += " — از " + item.Platform;
        _subtitle.Text = subtitle;
        _subtitle.Foreground = item.Status == DownloadStatus.Failed ? colors.AccentRed : colors.TextSecondary;

        // نوار پیشرفت + درصد
        var pct = item.TotalBytes > 0
            ? Math.Clamp(item.DownloadedBytes * 100.0 / item.TotalBytes, 0, 100)
            : 0;
        _fillCol.Width = new GridLength(pct / 100.0, GridUnitType.Star);
        _restCol.Width = new GridLength(1 - pct / 100.0, GridUnitType.Star);
        (_fillGridCache.Children[0] as Border)!.Background = color;

        var showPct = !_compact && item.Status == DownloadStatus.Downloading && item.TotalBytes > 0;
        _pct.IsVisible = showPct;
        if (showPct)
        {
            _pct.Text = Fmt.ToFa((long)Math.Round(pct)) + "٪";
            _pct.Foreground = color;
        }

        var showErr = !_compact && item.Status == DownloadStatus.Failed &&
                      !string.IsNullOrWhiteSpace(item.ErrorMessage);
        _err.IsVisible = showErr;
        if (showErr) _err.Text = item.ErrorMessage;

        if ((int)item.Status != _lastStatus)
        {
            _lastStatus = (int)item.Status;
            RebuildButtons(item, colors);
        }
    }

    private string _lastIconPath = "";
    private IBrush _lastIconColor = Brushes.Transparent;

    private void RebuildButtons(DownloadItem item, AppColors colors)
    {
        _buttons.Children.Clear();
        var hasButtons = false;

        switch (item.Status)
        {
            case DownloadStatus.Queued:
            case DownloadStatus.Connecting:
            case DownloadStatus.Downloading:
                _buttons.Children.Add(PillBtn(Ico.Pause, "توقف", colors.Track, colors.TextPrimary,
                    () => _engine.Pause(Id)));
                _buttons.Children.Add(PillBtn(Ico.Close, "لغو", colors.Track, colors.AccentRed,
                    () => _engine.Cancel(Id)));
                hasButtons = true;
                break;

            case DownloadStatus.Paused:
                _buttons.Children.Add(PillBtn(Ico.Play, "ادامه", colors.BrandGradient, Brushes.White,
                    () => _engine.Resume(Id), whiteIcon: true));
                _buttons.Children.Add(PillBtn(Ico.Edit, "تغییر نام", colors.Track, colors.TextPrimary,
                    () => _ = RenameFlowAsync(item)));
                _buttons.Children.Add(PillBtn(Ico.Close, "لغو", colors.Track, colors.AccentRed,
                    () => _engine.Cancel(Id)));
                hasButtons = true;
                break;

            case DownloadStatus.Completed:
                _buttons.Children.Add(PillBtn(Ico.Folder, "باز کردن پوشه", colors.BrandGradient, Brushes.White,
                    OpenFolder, whiteIcon: true));
                _buttons.Children.Add(PillBtn(Ico.Edit, "تغییر نام", colors.Track, colors.TextPrimary,
                    () => _ = RenameFlowAsync(item)));
                _buttons.Children.Add(PillBtn(Ico.Delete, "حذف از لیست", colors.Track, colors.AccentRed,
                    () => _engine.RemoveCompleted(Id)));
                hasButtons = true;
                break;

            case DownloadStatus.Failed:
                _buttons.Children.Add(PillBtn(Ico.Refresh, "تلاش دوباره", colors.BrandGradient, Brushes.White,
                    () => _engine.Resume(Id), whiteIcon: true));
                _buttons.Children.Add(PillBtn(Ico.Edit, "تغییر نام", colors.Track, colors.TextPrimary,
                    () => _ = RenameFlowAsync(item)));
                _buttons.Children.Add(PillBtn(Ico.Delete, "حذف", colors.Track, colors.AccentRed,
                    () => _engine.Cancel(Id)));
                hasButtons = true;
                break;
        }

        _buttons.IsVisible = hasButtons;
    }

    /// <summary>دکمه کوچک با آیکون + متن — یکدست در همه کارتها</summary>
    private Button PillBtn(string icon, string label, IBrush bg, IBrush fg, Action onClick,
        bool whiteIcon = false)
    {
        var content = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            VerticalAlignment = VerticalAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Center
        };
        content.Children.Add(Ico.Make(icon, 14, whiteIcon ? Brushes.White : fg));
        content.Children.Add(new TextBlock
        {
            Text = label,
            FontSize = 12,
            FontWeight = FontWeight.Medium,
            Foreground = fg,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(6, 0, 0, 0)
        });
        var b = new Button
        {
            Content = content,
            Height = 32,
            Padding = new Thickness(13, 0, 13, 0),
            CornerRadius = new CornerRadius(11),
            Background = bg,
            VerticalContentAlignment = VerticalAlignment.Center
        };
        b.Click += (_, _) => { try { onClick(); } catch (Exception ex) { _notify("خطا: " + ex.Message, true); } };
        return b;
    }

    private void OpenFolder()
    {
        var path = _engine.OpenablePath(Id);
        if (path == null)
        {
            _notify("فایل پیدا نشد", true);
            return;
        }
        try
        {
            if (OperatingSystem.IsWindows())
            {
                Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{path}\"")
                {
                    UseShellExecute = true
                });
            }
            else
            {
                Process.Start(new ProcessStartInfo("xdg-open", Path.GetDirectoryName(path) ?? ".")
                {
                    UseShellExecute = true
                });
            }
        }
        catch (Exception ex)
        {
            _notify("باز کردن پوشه ممکن نشد: " + ex.Message, true);
        }
    }

    private async Task RenameFlowAsync(DownloadItem item)
    {
        var colors = AppTheme.Colors;
        var dialog = new Window
        {
            Title = "تغییر نام فایل",
            Width = 440,
            Height = 210,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            CanResize = false,
            FontFamily = new FontFamily(FontUri),
            FlowDirection = FlowDirection.RightToLeft,
            Background = colors.Background
        };
        var stack = new StackPanel { Margin = new Thickness(22), Spacing = 12 };
        stack.Children.Add(new TextBlock
        {
            Text = "نام جدید فایل را بنویس:",
            FontSize = 14.5,
            FontWeight = FontWeight.Bold,
            Foreground = colors.TextPrimary
        });
        var box = new TextBox
        {
            Text = item.FileName,
            FontSize = 14,
            CornerRadius = new CornerRadius(12),
            Height = 44,
            Background = colors.Track,
            Foreground = colors.TextPrimary,
            BorderBrush = colors.CardBorder,
            VerticalContentAlignment = VerticalAlignment.Center
        };
        stack.Children.Add(box);
        var btnRow = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 8,
            HorizontalAlignment = HorizontalAlignment.Left
        };
        var ok = new Button
        {
            Content = "ذخیره",
            Padding = new Thickness(26, 9, 26, 9),
            CornerRadius = new CornerRadius(12),
            Background = colors.BrandGradient,
            Foreground = Brushes.White,
            FontWeight = FontWeight.Bold
        };
        var cancel = new Button
        {
            Content = "انصراف",
            Padding = new Thickness(22, 9, 22, 9),
            CornerRadius = new CornerRadius(12),
            Background = colors.Track,
            Foreground = colors.TextPrimary
        };
        ok.Click += (_, _) =>
        {
            var err = _engine.Rename(Id, box.Text ?? "");
            if (err != null)
            {
                _notify(err, true);
                return;
            }
            _notify("نام فایل تغییر کرد", false);
            dialog.Close();
        };
        cancel.Click += (_, _) => dialog.Close();
        btnRow.Children.Add(ok);
        btnRow.Children.Add(cancel);
        stack.Children.Add(btnRow);
        dialog.Content = stack;
        dialog.Opened += (_, _) => { box.Focus(); box.SelectAll(); };
        await dialog.ShowDialog(_owner);
    }

    private const string FontUri = "avares://SakhandDm/Assets/Fonts/#Vazirmatn";

    // ---------- ابزار وضعیت — همان نگاشت کارت اندروید ----------

    public static IBrush StatusColor(DownloadStatus s, AppColors c) => s switch
    {
        DownloadStatus.Downloading => c.Blue,
        DownloadStatus.Queued => c.Purple,
        DownloadStatus.Connecting => c.Purple,
        DownloadStatus.Paused => c.AccentAmber,
        DownloadStatus.Completed => c.AccentGreen,
        DownloadStatus.Failed => c.AccentRed,
        _ => c.TextSecondary
    };

    public static string StatusIcon(DownloadStatus s) => s switch
    {
        DownloadStatus.Downloading => Ico.Download,
        DownloadStatus.Queued => Ico.CloudDown,
        DownloadStatus.Connecting => Ico.CloudDown,
        DownloadStatus.Paused => Ico.Pause,
        DownloadStatus.Completed => Ico.CheckCircle,
        DownloadStatus.Failed => Ico.ErrorOutline,
        _ => Ico.Inbox
    };

    public static IBrush Tint(IBrush brush, double alpha)
    {
        if (brush is SolidColorBrush sc)
            return new SolidColorBrush(sc.Color, (byte)(255 * alpha));
        return brush;
    }
}
