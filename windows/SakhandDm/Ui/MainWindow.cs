using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Controls.Primitives;
using Avalonia.Styling;
using Avalonia.Threading;
using SakhandDm.Core;

namespace SakhandDm.Ui;

/// <summary>
/// پنجره اصلی ویندوز — پورت ۱:۱ رابط اندروید (AppRoot + HomeScreen + DownloadsScreen + AboutScreen)
/// با دو تب پایین، هدر گرادیانی متحرک، کارت‌های دانلود و صفحه درباره.
/// </summary>
public sealed class MainWindow : Window
{
    public const string FontUri = "avares://SakhandDm/Assets/Fonts/#Vazirmatn";
    public static bool SnapDemo;

    private readonly DownloadEngine _engine;
    private readonly string _appData;

    // اسکلت
    private Grid _contentGrid = null!;
    private ContentControl _pageHost = null!;
    private Border _navBar = null!;
    private readonly List<(Button Btn, Border Pill, TextBlock Label)> _tabs = new();
    private Border _snack = null!;
    private TextBlock _snackText = null!;
    private DispatcherTimer? _snackTimer;

    // وضعیت
    private string _current = "home";
    private string _mode = "auto"; // auto / 1080 / 720 / 480 / audio
    private bool _loading;
    private string _urlText = "";
    private int _filter; // 0 همه / 1 در جریان / 2 تمام‌شده

    // عناصر صفحه خانه
    private TextBox? _urlBox;
    private Border? _urlField;
    private Button? _startBtn;
    private TextBlock? _startLabel;
    private Border? _detectRow;
    private TextBlock? _detectLabel;
    private Border? _qualityBlock;
    private readonly List<Button> _chips = new();
    private TextBlock? _loadingRow;
    private Border? _activeSection;
    private StackPanel? _activeCards;
    private Border? _hero;
    private LinearGradientBrush? _heroBrush;
    private double _phase;
    private DispatcherTimer? _shimmer;

    // ردیف‌های دانلود
    private List<DownloadRow> _rows = new();
    private List<DownloadRow> _compactRows = new();

    public MainWindow(DownloadEngine engine)
    {
        _engine = engine;
        _appData = DownloadEngine.DefaultAppDataDir();

        Title = "Download Manager";
        Width = 1000;
        Height = 680;
        MinWidth = 840;
        MinHeight = 560;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        FontFamily = new FontFamily(FontUri);
        FlowDirection = FlowDirection.RightToLeft;
        try
        {
            Icon = new WindowIcon(AssetLoader.Open(new Uri("avares://SakhandDm/app.ico")));
        }
        catch { /* آیکون اختیاری است */ }

        if (SnapDemo)
            _urlText = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

        BuildUi();
        RebuildRows();

        _engine.ItemsChanged += OnItemsChanged;
        _engine.ItemUpdated += OnItemUpdated;
        AppTheme.Changed += OnThemeChanged;
        Closing += (_, _) =>
        {
            _engine.ItemsChanged -= OnItemsChanged;
            _engine.ItemUpdated -= OnItemUpdated;
            AppTheme.Changed -= OnThemeChanged;
            _shimmer?.Stop();
        };

        _shimmer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(90) };
        _shimmer.Tick += (_, _) => AnimateHero();
        _shimmer.Start();
    }

    // ===============================================================
    // اسکلت پنجره — محتوا + نوار پایین (مثل Scaffold اندروید)
    // ===============================================================

    private void BuildUi()
    {
        var colors = AppTheme.Colors;

        var root = new Grid
        {
            RowDefinitions =
            {
                new RowDefinition(1, GridUnitType.Star),
                new RowDefinition(GridLength.Auto)
            },
            Background = colors.Background
        };

        // ---- سطر محتوا: ستون وسط‌چین با عرض گوشی (مرتب مثل اندروید) ----
        _contentGrid = new Grid();
        var center = new Grid
        {
            MaxWidth = 680,
            HorizontalAlignment = HorizontalAlignment.Center
        };
        _pageHost = new ContentControl { HorizontalContentAlignment = HorizontalAlignment.Stretch };
        center.Children.Add(_pageHost);
        _contentGrid.Children.Add(center);

        // اسنک‌بار شناور (مثل Snackbar اندروید)
        _snack = new Border
        {
            CornerRadius = new CornerRadius(22),
            Background = AppTheme.Colors.SnackBg,
            Padding = new Thickness(22, 11, 22, 11),
            VerticalAlignment = VerticalAlignment.Bottom,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 0, 0, 18),
            IsHitTestVisible = false,
            IsVisible = false,
            MaxWidth = 560
        };
        _snackText = new TextBlock
        {
            FontSize = 13,
            Foreground = Brushes.White,
            TextAlignment = TextAlignment.Center,
            TextWrapping = TextWrapping.Wrap
        };
        _snack.Child = _snackText;
        _contentGrid.Children.Add(_snack);

        Grid.SetRow(_contentGrid, 0);
        root.Children.Add(_contentGrid);

        // ---- نوار پایین: دو تب با قرص بنفش (مثل NavigationBar اندروید) ----
        _navBar = new Border
        {
            Background = colors.Surface,
            BorderBrush = colors.CardBorder,
            BorderThickness = new Thickness(0, 1, 0, 0),
            Padding = new Thickness(0, 6, 0, 6)
        };
        var navGrid = new Grid
        {
            ColumnDefinitions = { new ColumnDefinition(1, GridUnitType.Star), new ColumnDefinition(1, GridUnitType.Star) },
            MaxWidth = 680,
            HorizontalAlignment = HorizontalAlignment.Center
        };
        _tabs.Clear();
        var tabHome = NavTab(Ico.Home, "خانه", 0);
        var tabDownloads = NavTab(Ico.Download, "دانلودها", 1);
        tabHome.Click += (_, _) => Navigate("home");
        tabDownloads.Click += (_, _) => Navigate("downloads");
        Grid.SetColumn(tabHome, 0);
        Grid.SetColumn(tabDownloads, 1);
        navGrid.Children.Add(tabHome);
        navGrid.Children.Add(tabDownloads);
        _navBar.Child = navGrid;
        Grid.SetRow(_navBar, 1);
        root.Children.Add(_navBar);

        Content = root;
        Navigate(_current, force: true);
    }

    private Button NavTab(string icon, string label, int index)
    {
        var pill = new Border
        {
            Width = 56,
            Height = 30,
            CornerRadius = new CornerRadius(15),
            HorizontalAlignment = HorizontalAlignment.Center
        };
        var labelTb = new TextBlock
        {
            Text = label,
            FontSize = 11.5,
            TextAlignment = TextAlignment.Center,
            Margin = new Thickness(0, 3, 0, 0)
        };
        var stack = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        stack.Children.Add(pill);
        stack.Children.Add(labelTb);
        var btn = new Button
        {
            Content = stack,
            Padding = new Thickness(0),
            Background = Brushes.Transparent,
            Height = 62,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Tag = index
        };
        _tabs.Add((btn, pill, labelTb));
        return btn;
    }

    private void RefreshNav()
    {
        var colors = AppTheme.Colors;
        var states = new[] { _current == "home", _current == "downloads" };
        var icons = new[] { Ico.Home, Ico.Download };
        for (var i = 0; i < _tabs.Count && i < 2; i++)
        {
            var (btn, pill, label) = _tabs[i];
            var selected = states[i];
            pill.Child = Ico.Make(icons[i], 21, selected ? colors.Purple : colors.TextSecondary);
            pill.Background = selected ? colors.NavPill : Brushes.Transparent;
            label.Foreground = selected ? colors.Purple : colors.TextSecondary;
            label.FontWeight = selected ? FontWeight.Bold : FontWeight.Normal;
        }
    }

    // ===============================================================
    // ناوبری — صفحه درباره نوار پایین را مخفی میکند (مثل اندروید)
    // ===============================================================

    internal void Navigate(string page, bool force = false)
    {
        if (_current == page && !force) return;
        _current = page;
        _navBar.IsVisible = page != "about";
        _pageHost.Content = page switch
        {
            "downloads" => BuildDownloads(),
            "about" => BuildAbout(),
            _ => BuildHome()
        };
        RefreshNav();
    }

    // ===============================================================
    // صفحه خانه — پورت HomeScreen.kt
    // ===============================================================

    private Control BuildHome()
    {
        var colors = AppTheme.Colors;
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Hidden };
        var root = new StackPanel { Margin = new Thickness(0, 16, 0, 0) };

        // ---- هدر گرادیانی متحرک ----
        _heroBrush = MakeHeroBrush();
        _hero = new Border
        {
            Background = _heroBrush,
            CornerRadius = new CornerRadius(26),
            Padding = new Thickness(18, 16, 18, 16)
        };
        var heroRow = new Grid
        {
            ColumnDefinitions = { new ColumnDefinition(GridLength.Auto), new ColumnDefinition(1, GridUnitType.Star), new ColumnDefinition(GridLength.Auto) }
        };
        var heroCircle = new Border
        {
            Width = 48,
            Height = 48,
            CornerRadius = new CornerRadius(24),
            Background = SolidColorBrush.Parse("#33FFFFFF"),
            Child = Ico.Make(Ico.Download, 25, Brushes.White),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };
        Grid.SetColumn(heroCircle, 0);
        heroRow.Children.Add(heroCircle);

        var heroText = new StackPanel
        {
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(12, 0, 12, 0)
        };
        heroText.Children.Add(new TextBlock
        {
            Text = "Download Manager",
            FontSize = 19,
            FontWeight = FontWeight.Bold,
            Foreground = Brushes.White
        });
        heroText.Children.Add(new TextBlock
        {
            Text = "دانلود سریع فایل و ویدیو",
            FontSize = 12,
            Foreground = SolidColorBrush.Parse("#E0FFFFFF"),
            Margin = new Thickness(0, 2, 0, 0)
        });
        Grid.SetColumn(heroText, 1);
        heroRow.Children.Add(heroText);

        var infoBtn = new Button
        {
            Content = Ico.Make(Ico.Info, 22, Brushes.White),
            Padding = new Thickness(6),
            Background = Brushes.Transparent,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };
        infoBtn.Click += (_, _) => Navigate("about");
        Grid.SetColumn(infoBtn, 2);
        heroRow.Children.Add(infoBtn);
        _hero.Child = heroRow;
        root.Children.Add(_hero);

        // ---- فیلد لینک با آیکون پیوند + دکمه چسباندن (مثل OutlinedTextField اندروید) ----
        _urlField = new Border
        {
            Background = colors.Card,
            BorderBrush = colors.CardBorder,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(16),
            Height = 52,
            Margin = new Thickness(0, 18, 0, 0)
        };
        var fieldGrid = new Grid
        {
            ColumnDefinitions = { new ColumnDefinition(GridLength.Auto), new ColumnDefinition(1, GridUnitType.Star), new ColumnDefinition(GridLength.Auto) },
            VerticalAlignment = VerticalAlignment.Center
        };
        var linkIcon = Ico.Make(Ico.Link, 20, colors.TextSecondary);
        linkIcon.VerticalAlignment = VerticalAlignment.Center;
        linkIcon.Margin = new Thickness(0, 0, 10, 0);
        Grid.SetColumn(linkIcon, 0);
        fieldGrid.Children.Add(linkIcon);

        _urlBox = new TextBox
        {
            Text = _urlText,
            Watermark = "لینک را اینجا بچسبانید…",
            FontSize = 14,
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Padding = new Thickness(0),
            MinHeight = 0,
            Foreground = colors.TextPrimary,
            CaretBrush = colors.Purple,
            VerticalAlignment = VerticalAlignment.Center,
            VerticalContentAlignment = VerticalAlignment.Center
        };
        _urlBox.PropertyChanged += (_, e) =>
        {
            if (e.Property == TextBox.TextProperty)
            {
                _urlText = _urlBox.Text ?? "";
                RefreshDetect();
                RefreshStartButton();
            }
        };
        _urlBox.GotFocus += (_, _) => _urlField!.BorderBrush = colors.Purple;
        _urlBox.LostFocus += (_, _) => _urlField!.BorderBrush = colors.CardBorder;
        Grid.SetColumn(_urlBox, 1);
        fieldGrid.Children.Add(_urlBox);

        var pasteBtn = new Button
        {
            Content = Ico.Make(Ico.Paste, 20, colors.TextSecondary),
            Width = 38,
            Height = 38,
            Padding = new Thickness(0),
            Background = Brushes.Transparent,
            HorizontalContentAlignment = HorizontalAlignment.Center,
            VerticalContentAlignment = VerticalAlignment.Center,
            Margin = new Thickness(8, 0, 0, 0),
            VerticalAlignment = VerticalAlignment.Center
        };
        pasteBtn.Click += async (_, _) => await PasteClickedAsync();
        Grid.SetColumn(pasteBtn, 2);
        fieldGrid.Children.Add(pasteBtn);
        _urlField.Child = fieldGrid;
        root.Children.Add(_urlField);

        // ---- ردیف تشخیص لینک (تیک سبز) ----
        _detectRow = new Border
        {
            IsVisible = false,
            Margin = new Thickness(2, 10, 2, 0)
        };
        var detectStack = new StackPanel { Orientation = Orientation.Horizontal };
        detectStack.Children.Add(Ico.Make(Ico.CheckCircle, 17, colors.AccentGreen));
        _detectLabel = new TextBlock
        {
            FontSize = 12.5,
            Foreground = colors.AccentGreen,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(7, 0, 0, 0)
        };
        detectStack.Children.Add(_detectLabel);
        _detectRow.Child = detectStack;
        root.Children.Add(_detectRow);

        // ---- بلوک کیفیت (فقط برای لینک اجتماعی) ----
        _qualityBlock = new Border
        {
            IsVisible = false,
            Margin = new Thickness(0, 12, 0, 0)
        };
        var qStack = new StackPanel();
        qStack.Children.Add(new TextBlock
        {
            Text = "کیفیت",
            FontSize = 15,
            FontWeight = FontWeight.Bold,
            Foreground = colors.TextPrimary,
            Margin = new Thickness(2, 0, 2, 8)
        });
        var chips = new WrapPanel();
        _chips.Clear();
        AddChip(chips, "بهترین", "auto");
        AddChip(chips, "1080p", "1080");
        AddChip(chips, "720p", "720");
        AddChip(chips, "480p", "480");
        AddChip(chips, "فقط صدا", "audio");
        qStack.Children.Add(chips);
        _qualityBlock.Child = qStack;
        root.Children.Add(_qualityBlock);

        // ---- دکمه اصلی گرادیانی ----
        _startBtn = new Button
        {
            Height = 52,
            CornerRadius = new CornerRadius(18),
            Background = colors.BrandGradient,
            Margin = new Thickness(0, 14, 0, 0),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Padding = new Thickness(0)
        };
        _startLabel = new TextBlock
        {
            Text = "شروع دانلود",
            FontSize = 15,
            FontWeight = FontWeight.Bold,
            Foreground = Brushes.White,
            TextAlignment = TextAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };
        _startBtn.Content = _startLabel;
        _startBtn.Click += async (_, _) => await StartDownloadAsync();
        root.Children.Add(_startBtn);

        // ---- ردیف انتظار استخراج ----
        _loadingRow = new TextBlock
        {
            Text = "در حال استخراج لینک رسانه…",
            FontSize = 12.5,
            Foreground = colors.Purple,
            TextAlignment = TextAlignment.Center,
            Margin = new Thickness(0, 12, 0, 0),
            IsVisible = false
        };
        root.Children.Add(_loadingRow);

        // ---- بخش «در حال دانلود» با ۳ کارت فشرده ----
        _activeSection = new Border
        {
            IsVisible = false,
            Margin = new Thickness(0, 22, 0, 0)
        };
        var activeStack = new StackPanel();
        var headGrid = new Grid
        {
            ColumnDefinitions = { new ColumnDefinition(1, GridUnitType.Star), new ColumnDefinition(GridLength.Auto) }
        };
        headGrid.Children.Add(new TextBlock
        {
            Text = "در حال دانلود",
            FontSize = 17,
            FontWeight = FontWeight.Bold,
            Foreground = colors.TextPrimary,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(2, 0, 2, 0)
        });
        var seeAll = new Button
        {
            Content = "همه",
            FontSize = 13,
            FontWeight = FontWeight.Bold,
            Foreground = colors.Purple,
            Background = Brushes.Transparent,
            Padding = new Thickness(10, 2, 10, 2)
        };
        seeAll.Click += (_, _) => Navigate("downloads");
        Grid.SetColumn(seeAll, 1);
        headGrid.Children.Add(seeAll);
        activeStack.Children.Add(headGrid);

        _activeCards = new StackPanel();
        activeStack.Children.Add(_activeCards);
        _activeSection.Child = activeStack;
        root.Children.Add(_activeSection);

        // ---- پانویس ----
        root.Children.Add(new TextBlock
        {
            Text = "لینک‌های یوتیوب، اینستاگرام و پینترست خودکار شناسایی می‌شوند — فایل‌ها در پوشه Downloads ویندوز ذخیره می‌شوند",
            FontSize = 12,
            Foreground = colors.TextSecondary,
            TextAlignment = TextAlignment.Center,
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(8, 20, 8, 12)
        });

        scroll.Content = root;
        RefreshDetect();
        RefreshStartButton();
        RefreshActiveCards();
        return scroll;
    }

    private static LinearGradientBrush MakeHeroBrush() => new()
    {
        StartPoint = new RelativePoint(0.05, 0.1, RelativeUnit.Relative),
        EndPoint = new RelativePoint(0.95, 0.95, RelativeUnit.Relative),
        GradientStops =
        {
            new GradientStop(Color.Parse("#8B5CF6"), 0),
            new GradientStop(Color.Parse("#3B82F6"), 0.55),
            new GradientStop(Color.Parse("#EB3B82F6"), 1)
        }
    };

    private void AnimateHero()
    {
        if (_hero == null || _heroBrush == null || _current != "home") return;
        _phase += 0.045;
        if (_phase > Math.PI * 2) _phase -= Math.PI * 2;
        var s = Math.Sin(_phase) * 0.5 + 0.5; // ۰..۱
        var sx = -0.25 + 0.9 * s;
        var ex = 0.45 + 0.9 * s;
        _heroBrush.StartPoint = new RelativePoint(sx, 0.12, RelativeUnit.Relative);
        _heroBrush.EndPoint = new RelativePoint(ex, 0.92, RelativeUnit.Relative);
    }

    private void AddChip(WrapPanel host, string label, string key)
    {
        var colors = AppTheme.Colors;
        var chip = new Button
        {
            Content = label,
            Padding = new Thickness(16, 7, 16, 7),
            FontSize = 12.5,
            CornerRadius = new CornerRadius(18),
            Margin = new Thickness(0, 0, 0, 8),
            Tag = key
        };
        chip.Click += (_, _) =>
        {
            _mode = key;
            RefreshChips();
        };
        _chips.Add(chip);
        host.Children.Add(chip);
    }

    private void RefreshChips()
    {
        var colors = AppTheme.Colors;
        foreach (var chip in _chips)
        {
            var selected = _mode == (string)chip.Tag!;
            chip.Background = selected ? colors.ChipSelectedBg : colors.Card;
            chip.Foreground = selected ? colors.ChipSelectedFg : colors.TextSecondary;
            chip.FontWeight = selected ? FontWeight.Bold : FontWeight.Normal;
            chip.BorderBrush = selected ? colors.Purple : colors.CardBorder;
        }
    }

    private void RefreshDetect()
    {
        if (_detectRow == null || _urlBox == null) return;
        var platform = SocialDetector.Detect(_urlBox.Text);
        var colors = AppTheme.Colors;
        var hasPlatform = platform != null;
        _detectRow.IsVisible = hasPlatform;
        _qualityBlock!.IsVisible = hasPlatform;
        if (hasPlatform)
        {
            _detectLabel!.Text = $"لینک {platform.Value.Label()} شناسایی شد";
            _detectLabel.Foreground = colors.AccentGreen;
        }
        RefreshChips();
    }

    private void RefreshStartButton()
    {
        if (_startBtn == null || _startLabel == null) return;
        var colors = AppTheme.Colors;
        var platform = SocialDetector.Detect(_urlBox?.Text);
        _startLabel.Text = _loading
            ? "لطفاً صبر کن…"
            : platform != null ? "استخراج و دانلود" : "شروع دانلود";
        _startBtn.Background = _loading ? colors.Track : colors.BrandGradient;
        _startLabel.Foreground = _loading ? colors.TextSecondary : Brushes.White;
        _startBtn.IsEnabled = !_loading;
        if (_loadingRow != null) _loadingRow.IsVisible = _loading;
    }

    private async System.Threading.Tasks.Task PasteClickedAsync()
    {
        try
        {
            var tl = TopLevel.GetTopLevel(this);
            var text = tl?.Clipboard != null ? await tl.Clipboard.GetTextAsync() : null;
            if (!string.IsNullOrWhiteSpace(text) && _urlBox != null)
            {
                _urlBox.Text = text.Trim();
                _urlBox.CaretIndex = _urlBox.Text?.Length ?? 0;
                ShowSnack("لینک چسبانده شد");
            }
            else ShowSnack("کلیپ‌بورد خالی است", true);
        }
        catch (Exception ex)
        {
            ShowSnack("دسترسی به کلیپ‌بورد ممکن نشد: " + ex.Message, true);
        }
    }

    private async System.Threading.Tasks.Task StartDownloadAsync()
    {
        var link = _urlBox?.Text?.Trim();
        if (string.IsNullOrEmpty(link))
        {
            ShowSnack("اول لینک را وارد کن", true);
            return;
        }
        var platform = SocialDetector.Detect(link);
        try
        {
            _loading = true;
            RefreshStartButton();
            if (platform != null)
            {
                ShowSnack($"در حال استخراج لینک از {platform.Value.Label()}…");
                var resolved = await SocialResolver.ResolveAsync(
                    link, _mode == "audio" ? "1080" : _mode, _mode == "audio", platform.Value);
                var added = _engine.Add(resolved.Url, resolved.FileName, resolved.Mime, true, platform.Value.Label());
                if (added == null) ShowSnack("این دانلود از قبل در جریان است", true);
                else ShowSnack("دانلود شروع شد");
                if (_urlBox != null) _urlBox.Text = "";
            }
            else
            {
                var added = _engine.Add(link);
                if (added == null) ShowSnack("این دانلود از قبل در جریان است", true);
                else ShowSnack("دانلود شروع شد");
                if (_urlBox != null) _urlBox.Text = "";
            }
        }
        catch (SocialException ex)
        {
            ShowSnack(ex.Message, true);
        }
        catch (Exception ex)
        {
            ShowSnack("خطا: " + ex.Message, true);
        }
        finally
        {
            _loading = false;
            RefreshStartButton();
        }
    }

    private void RefreshActiveCards()
    {
        if (_activeSection == null || _activeCards == null) return;
        var items = SnapDemo ? DemoItems() : _engine.Snapshot();
        var active = items.Where(i => i.IsActive).Take(3).ToList();
        _activeSection.IsVisible = active.Count > 0;
        _activeCards.Children.Clear();
        _compactRows.Clear();
        foreach (var item in active)
        {
            var row = new DownloadRow(_engine, item, (msg, err) => ShowSnack(msg, err), this, compact: true);
            _compactRows.Add(row);
            _activeCards.Children.Add(row);
        }
    }

    // ===============================================================
    // صفحه دانلودها — پورت DownloadsScreen.kt
    // ===============================================================

    private Control BuildDownloads()
    {
        var colors = AppTheme.Colors;
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Hidden };
        var root = new StackPanel { Margin = new Thickness(0, 14, 0, 0) };

        root.Children.Add(new TextBlock
        {
            Text = "مدیریت دانلودها",
            FontSize = 19,
            FontWeight = FontWeight.Bold,
            Foreground = colors.TextPrimary,
            Margin = new Thickness(2, 0, 2, 12)
        });

        // فیلترها — همه / در جریان / تمام‌شده
        var chipsRow = new WrapPanel();
        AddFilterChip(chipsRow, "همه", 0);
        AddFilterChip(chipsRow, "در جریان", 1);
        AddFilterChip(chipsRow, "تمام‌شده", 2);
        root.Children.Add(chipsRow);

        var items = SnapDemo ? DemoItems() : _engine.Snapshot();
        var filtered = _filter switch
        {
            1 => items.Where(i => i.IsActive || i.Status == DownloadStatus.Paused || i.Status == DownloadStatus.Failed).ToList(),
            2 => items.Where(i => i.Status == DownloadStatus.Completed).ToList(),
            _ => items.ToList()
        };

        if (filtered.Count == 0)
        {
            // حالت خالی — مثل EmptyState اندروید
            var empty = new StackPanel
            {
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 60, 0, 24)
            };
            var circle = new Border
            {
                Width = 88,
                Height = 88,
                CornerRadius = new CornerRadius(44),
                Background = SolidColorBrush.Parse("#1A8B5CF6"),
                Child = Ico.Make(Ico.Inbox, 42, SolidColorBrush.Parse("#B38B5CF6")),
                HorizontalAlignment = HorizontalAlignment.Center
            };
            empty.Children.Add(circle);
            empty.Children.Add(new TextBlock
            {
                Text = "هنوز دانلودی اینجا نیست — از صفحه اصلی یک لینک اضافه کن",
                FontSize = 13,
                Foreground = colors.TextSecondary,
                TextAlignment = TextAlignment.Center,
                TextWrapping = TextWrapping.Wrap,
                MaxWidth = 360,
                Margin = new Thickness(0, 14, 0, 0)
            });
            root.Children.Add(empty);
        }
        else
        {
            var list = new StackPanel();
            for (var i = 0; i < filtered.Count; i++)
            {
                var item = filtered[i];
                var row = new DownloadRow(_engine, item, (msg, err) => ShowSnack(msg, err), this);
                // ردیف تازه از وضعیت فعلی بساز
                var old = _rows.FirstOrDefault(r => r.Id == item.Id);
                if (old != null) row.Update(item);
                list.Children.Add(row);
                if (i < filtered.Count - 1)
                    list.Children.Add(new Border { Height = 10 });
            }
            // نگاشت برای بهروزرسانی زنده
            _rows = filtered.Select(item => list.Children
                    .OfType<DownloadRow>()
                    .First(r => r.Id == item.Id))
                .ToList();
            root.Children.Add(list);
        }

        scroll.Content = root;
        return scroll;
    }

    private void AddFilterChip(WrapPanel host, string label, int key)
    {
        var colors = AppTheme.Colors;
        var chip = new Button
        {
            Content = label,
            Padding = new Thickness(16, 7, 16, 7),
            FontSize = 12.5,
            CornerRadius = new CornerRadius(18),
            Margin = new Thickness(0, 0, 8, 8),
            Tag = key
        };
        chip.Click += (_, _) =>
        {
            _filter = key;
            Navigate("downloads", force: true);
        };
        var selected = _filter == key;
        chip.Background = selected ? colors.ChipSelectedBg : colors.Card;
        chip.Foreground = selected ? colors.ChipSelectedFg : colors.TextSecondary;
        chip.FontWeight = selected ? FontWeight.Bold : FontWeight.Normal;
        chip.BorderBrush = selected ? colors.Purple : colors.CardBorder;
        host.Children.Add(chip);
    }

    private void RebuildRows()
    {
        var items = SnapDemo ? DemoItems() : _engine.Snapshot();
        _rows = items.Select(i => new DownloadRow(_engine, i, (msg, err) => ShowSnack(msg, err), this)).ToList();
        RefreshActiveCards();
        if (_current == "downloads") Navigate("downloads", force: true);
    }

    private void OnItemsChanged()
    {
        Dispatcher.UIThread.Post(RebuildRows);
    }

    private void OnItemUpdated(string id)
    {
        Dispatcher.UIThread.Post(() =>
        {
            var item = (SnapDemo ? DemoItems() : _engine.Snapshot()).FirstOrDefault(i => i.Id == id);
            if (item == null) return;
            _rows.FirstOrDefault(r => r.Id == id)?.Update(item);
            _compactRows.FirstOrDefault(r => r.Id == id)?.Update(item);
        });
    }

    // ===============================================================
    // صفحه درباره — پورت AboutScreen.kt
    // ===============================================================

    private Control BuildAbout()
    {
        var colors = AppTheme.Colors;
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Hidden };
        var root = new StackPanel
        {
            Width = 430,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 14, 0, 0)
        };

        // دکمه بازگشت
        var backRow = new Grid();
        var back = new Button
        {
            Content = Ico.Make(Ico.Back, 22, colors.TextPrimary),
            Width = 42,
            Height = 42,
            Padding = new Thickness(0),
            Background = Brushes.Transparent,
            HorizontalAlignment = HorizontalAlignment.Left,
            HorizontalContentAlignment = HorizontalAlignment.Center,
            VerticalContentAlignment = VerticalAlignment.Center
        };
        back.Click += (_, _) => Navigate("home");
        Grid.SetColumn(back, 0);
        backRow.Children.Add(back);
        root.Children.Add(backRow);

        // آیکون برنامه داخل حلقه گرادیانی
        var ring = new Border
        {
            Width = 104,
            Height = 104,
            CornerRadius = new CornerRadius(52),
            Background = AppTheme.Colors.RingGradient,
            Padding = new Thickness(6),
            Margin = new Thickness(0, 6, 0, 0),
            HorizontalAlignment = HorizontalAlignment.Center
        };
        var inner = new Border
        {
            CornerRadius = new CornerRadius(46),
            ClipToBounds = true,
            Child = LogoImage(92)
        };
        ring.Child = inner;
        root.Children.Add(ring);

        root.Children.Add(new TextBlock
        {
            Text = "سهند مرامی",
            FontSize = 24,
            FontWeight = FontWeight.Bold,
            Foreground = colors.TextPrimary,
            TextAlignment = TextAlignment.Center,
            Margin = new Thickness(0, 14, 0, 0)
        });
        root.Children.Add(new TextBlock
        {
            Text = "سازنده و توسعه‌دهنده برنامه",
            FontSize = 13,
            Foreground = colors.TextSecondary,
            TextAlignment = TextAlignment.Center,
            Margin = new Thickness(0, 4, 0, 0)
        });

        // انتخاب تم — کلید دوحالته مثل اندروید
        root.Children.Add(new TextBlock
        {
            Text = "تم برنامه",
            FontSize = 16,
            FontWeight = FontWeight.Bold,
            Foreground = colors.TextPrimary,
            TextAlignment = TextAlignment.Center,
            Margin = new Thickness(0, 26, 0, 0)
        });
        var toggle = new Border
        {
            CornerRadius = new CornerRadius(18),
            Background = colors.Track,
            Padding = new Thickness(5),
            Margin = new Thickness(0, 12, 0, 0)
        };
        var togGrid = new Grid
        {
            ColumnDefinitions = { new ColumnDefinition(1, GridUnitType.Star), new ColumnDefinition(1, GridUnitType.Star) }
        };
        var lightBtn = ToggleOption(Ico.Sun, "روشن", AppTheme.IsLight);
        var darkBtn = ToggleOption(Ico.Moon, "تیره", !AppTheme.IsLight);
        lightBtn.Click += (_, _) => AppTheme.SetLight(_appData, true);
        darkBtn.Click += (_, _) => AppTheme.SetLight(_appData, false);
        Grid.SetColumn(lightBtn, 0);
        Grid.SetColumn(darkBtn, 1);
        togGrid.Children.Add(lightBtn);
        togGrid.Children.Add(darkBtn);
        toggle.Child = togGrid;
        root.Children.Add(toggle);

        // ردیف نسخه
        var ver = new Border
        {
            Background = colors.Card,
            BorderBrush = colors.CardBorder,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(18),
            Padding = new Thickness(18, 14, 18, 14),
            Margin = new Thickness(0, 20, 0, 0)
        };
        var verGrid = new Grid
        {
            ColumnDefinitions =
            {
                new ColumnDefinition(GridLength.Auto),
                new ColumnDefinition(1, GridUnitType.Star),
                new ColumnDefinition(GridLength.Auto)
            }
        };
        verGrid.Children.Add(new TextBlock
        {
            Text = "نسخه برنامه",
            FontSize = 13.5,
            Foreground = colors.TextSecondary,
            VerticalAlignment = VerticalAlignment.Center
        });
        var verVal = new TextBlock
        {
            Text = "۱٫۳",
            FontSize = 14,
            FontWeight = FontWeight.Bold,
            Foreground = colors.TextPrimary,
            VerticalAlignment = VerticalAlignment.Center
        };
        Grid.SetColumn(verVal, 2);
        verGrid.Children.Add(verVal);
        ver.Child = verGrid;
        root.Children.Add(ver);

        root.Children.Add(new TextBlock
        {
            Text = "طراحی و توسعه با عشق توسط سهند مرامی",
            FontSize = 12.5,
            Foreground = colors.Purple,
            TextAlignment = TextAlignment.Center,
            Margin = new Thickness(0, 26, 0, 24)
        });

        scroll.Content = root;
        return scroll;
    }

    private Button ToggleOption(string icon, string label, bool selected)
    {
        var colors = AppTheme.Colors;
        var stack = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };
        stack.Children.Add(Ico.Make(icon, 17, selected ? Brushes.White : colors.TextSecondary));
        stack.Children.Add(new TextBlock
        {
            Text = label,
            FontSize = 14,
            FontWeight = selected ? FontWeight.Bold : FontWeight.Normal,
            Foreground = selected ? Brushes.White : colors.TextSecondary,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(8, 0, 0, 0)
        });
        return new Button
        {
            Content = stack,
            Height = 44,
            Padding = new Thickness(0),
            CornerRadius = new CornerRadius(14),
            Background = selected ? colors.BrandGradient : Brushes.Transparent,
            HorizontalAlignment = HorizontalAlignment.Stretch
        };
    }

    private static Image LogoImage(double size)
    {
        try
        {
            return new Image
            {
                Source = new Avalonia.Media.Imaging.Bitmap(
                    AssetLoader.Open(new Uri("avares://SakhandDm/Assets/logo_round.png"))),
                Width = size,
                Height = size,
                Stretch = Stretch.UniformToFill
            };
        }
        catch
        {
            return new Image();
        }
    }

    private void OnThemeChanged()
    {
        Dispatcher.UIThread.Post(() =>
        {
            // منابع Fluent هم با تم اپ هماهنگ شوند (متن/اسکرول/سلیکشن)
            if (Application.Current != null)
                Application.Current.RequestedThemeVariant =
                    AppTheme.IsLight ? ThemeVariant.Light : ThemeVariant.Dark;
            BuildUi();
            RebuildRows();
        });
    }

    // ===============================================================
    // اسنک‌بار
    // ===============================================================

    private void ShowSnack(string message, bool isError = false)
    {
        Dispatcher.UIThread.Post(() =>
        {
            _snackText.Text = message;
            _snackText.Foreground = isError
                ? AppTheme.Colors.SnackErr
                : Brushes.White;
            _snack.IsVisible = true;
            _snackTimer?.Stop();
            _snackTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3.5) };
            _snackTimer.Tick += (_, _) =>
            {
                _snack.IsVisible = false;
                _snackTimer?.Stop();
            };
            _snackTimer.Start();
        });
    }

    // ===============================================================
    // داده نمایشی + تور اسکرین‌شات (فقط برای محیط بیلد)
    // ===============================================================

    private static List<DownloadItem> DemoItems() => new()
    {
        new DownloadItem
        {
            Id = "d1", Url = "https://youtu.be/demo", FileName = "amoozesh-video-dars2.mp4",
            TotalBytes = 734003200, DownloadedBytes = 308281344, SpeedBps = 5242880,
            Status = DownloadStatus.Downloading, IsSocial = true, Platform = "یوتیوب"
        },
        new DownloadItem
        {
            Id = "d2", Url = "https://pin.it/demo", FileName = "wallpaper-pack-4k.zip",
            TotalBytes = 157286400, DownloadedBytes = 157286400,
            Status = DownloadStatus.Completed, IsSocial = true, Platform = "پینترست"
        },
        new DownloadItem
        {
            Id = "d3", Url = "https://example.com/a.rar", FileName = "نرم‌افزار-آرشیو-بخش۱.rar",
            TotalBytes = 524288000, DownloadedBytes = 209715200,
            Status = DownloadStatus.Paused
        },
        new DownloadItem
        {
            Id = "d4", Url = "https://example.com/setup.exe", FileName = "installer_tool.exe",
            TotalBytes = 89128960, DownloadedBytes = 41943040,
            Status = DownloadStatus.Failed, ErrorMessage = "اتصال به سرور در میانه دانلود قطع شد"
        }
    };

    internal static void RunSnapTour(MainWindow w, string dir)
    {
        try { Directory.CreateDirectory(dir); } catch { }
        var steps = new List<Action>
        {
            () => Snap(w, Path.Combine(dir, "01-home-dark.png")),
            () => w.Navigate("downloads"),
            () => Snap(w, Path.Combine(dir, "02-downloads-dark.png")),
            () => w.Navigate("about"),
            () => Snap(w, Path.Combine(dir, "03-about-dark.png")),
            () => AppTheme.SetLight(w._appData, true),
            () => w.Navigate("home"),
            () => Snap(w, Path.Combine(dir, "04-home-light.png")),
            () => w.Navigate("downloads"),
            () => Snap(w, Path.Combine(dir, "05-downloads-light.png")),
            () => Dispatcher.UIThread.InvokeShutdown()
        };
        var i = 0;
        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(700) };
        timer.Tick += (_, _) =>
        {
            if (i >= steps.Count) { timer.Stop(); return; }
            steps[i]();
            i++;
            if (i >= steps.Count) timer.Stop();
        };
        timer.Start();
    }

    private static void Snap(Window w, string path)
    {
        try
        {
            var sz = w.ClientSize;
            var rtb = new RenderTargetBitmap(
                new PixelSize(Math.Max(1, (int)sz.Width), Math.Max(1, (int)sz.Height)),
                new Vector(96, 96));
            rtb.Render(w);
            using var fs = File.Create(path);
            rtb.Save(fs);
            Console.WriteLine("snap saved: " + path);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("snap failed: " + ex.Message);
        }
    }
}
