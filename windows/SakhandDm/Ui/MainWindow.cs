using System;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Platform;
using Avalonia.Threading;
using SakhandDm.Core;

namespace SakhandDm.Ui;

public sealed class MainWindow : Window
{
    private const string FontUri = "avares://SakhandDm/Assets/Fonts/#Vazirmatn";

    private readonly DownloadEngine _engine;
    private readonly string _appData;
    private readonly ObservableCollection<Control> _rows = new();

    private ContentControl _pageHost = null!;
    private StackPanel _navPanel = null!;
    private Button _tabHome = null!;
    private Button _tabDownloads = null!;
    private TextBlock _statusText = null!;
    private DispatcherTimer? _statusTimer;

    private string _current = "home";
    private string _quality = "auto";
    private bool _audioOnly;
    private TextBox? _urlBox;
    private Button? _startBtn;
    private TextBlock? _detectChip;
    private StackPanel? _chipsPanel;

    public MainWindow(DownloadEngine engine)
    {
        _engine = engine;
        _appData = DownloadEngine.DefaultAppDataDir();

        Title = "Download Manager";
        Width = 960;
        Height = 660;
        MinWidth = 780;
        MinHeight = 540;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        FontFamily = new FontFamily(FontUri);
        FlowDirection = FlowDirection.RightToLeft;
        try
        {
            Icon = new WindowIcon(AssetLoader.Open(new Uri("avares://SakhandDm/app.ico")));
        }
        catch { /* آیکون اختیاری است */ }

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
        };
    }

    // ---------------------------------------------------------------
    // اسکلت صفحه
    // ---------------------------------------------------------------

    private void BuildUi()
    {
        var colors = AppTheme.Colors;
        Background = colors.Background;

        var root = new Grid { RowDefinitions = { new RowDefinition(GridLength.Auto), new RowDefinition(1, GridUnitType.Star), new RowDefinition(GridLength.Auto), new RowDefinition(GridLength.Auto) } };

        // هدر
        root.Children.Add(BuildHeader());

        // محتوای صفحه
        _pageHost = new ContentControl { Margin = new Thickness(20, 14, 20, 0) };
        Grid.SetRow(_pageHost, 1);
        root.Children.Add(_pageHost);

        // نوار پایین
        var navBorder = new Border
        {
            BorderBrush = colors.CardBorder,
            BorderThickness = new Thickness(0, 1, 0, 0),
            Background = colors.Surface,
            Margin = new Thickness(0, 10, 0, 0)
        };
        _navPanel = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Center,
            Spacing = 10
        };
        _tabHome = NavButton("خانه");
        _tabDownloads = NavButton("دانلودها");
        _tabHome.Click += (_, _) => Navigate("home");
        _tabDownloads.Click += (_, _) => Navigate("downloads");
        _navPanel.Children.Add(_tabHome);
        _navPanel.Children.Add(_tabDownloads);
        navBorder.Child = _navPanel;
        Grid.SetRow(navBorder, 2);
        root.Children.Add(navBorder);

        // نوار وضعیت (اسنک‌بار)
        _statusText = new TextBlock
        {
            Margin = new Thickness(24, 6, 24, 10),
            TextAlignment = TextAlignment.Center,
            FontSize = 13,
            Foreground = colors.AccentGreen,
            TextWrapping = TextWrapping.Wrap
        };
        Grid.SetRow(_statusText, 3);
        root.Children.Add(_statusText);

        Content = root;
        RefreshNav();
        Navigate(_current, force: true);
    }

    private Control BuildHeader()
    {
        var colors = AppTheme.Colors;
        var header = new Border
        {
            Background = colors.Surface,
            Padding = new Thickness(18, 12, 18, 12)
        };
        var grid = new Grid
        {
            ColumnDefinitions = { new ColumnDefinition(GridLength.Star), new ColumnDefinition(GridLength.Auto) }
        };

        var right = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, VerticalAlignment = VerticalAlignment.Center };
        right.Children.Add(new Border
        {
            Width = 38, Height = 38, CornerRadius = new CornerRadius(11),
            ClipToBounds = true, Child = LogoImage(38)
        });
        var titleStack = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        titleStack.Children.Add(new TextBlock
        {
            Text = "دانلود منیجر", FontWeight = FontWeight.Bold, FontSize = 17,
            Foreground = colors.TextPrimary
        });
        titleStack.Children.Add(new TextBlock
        {
            Text = "توسعه سهند مرامی", FontSize = 11,
            Foreground = colors.TextSecondary
        });
        right.Children.Add(titleStack);
        Grid.SetColumn(right, 0);
        grid.Children.Add(right);

        var left = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
        var themeBtn = HeaderButton(AppTheme.IsLight ? "حالت تیره" : "حالت روشن");
        themeBtn.Click += (_, _) => AppTheme.SetLight(_appData, !AppTheme.IsLight);
        left.Children.Add(themeBtn);
        var aboutBtn = HeaderButton("درباره");
        aboutBtn.Click += (_, _) => Navigate("about");
        left.Children.Add(aboutBtn);
        Grid.SetColumn(left, 1);
        grid.Children.Add(left);

        header.Child = grid;
        return header;
    }

    private static Image LogoImage(double size)
    {
        try
        {
            return new Image
            {
                Source = new Avalonia.Media.Imaging.Bitmap(
                    AssetLoader.Open(new Uri("avares://SakhandDm/Assets/logo_round.png"))),
                Width = size, Height = size,
                Stretch = Stretch.UniformToFill
            };
        }
        catch
        {
            return new Image();
        }
    }

    private Button HeaderButton(string label) => new()
    {
        Content = label,
        Padding = new Thickness(15, 7, 15, 7),
        FontSize = 12.5,
        CornerRadius = new CornerRadius(12),
        Background = AppTheme.Colors.Track,
        Foreground = AppTheme.Colors.TextPrimary
    };

    private Button NavButton(string label) => new()
    {
        Content = label,
        Padding = new Thickness(34, 8, 34, 8),
        FontSize = 14,
        CornerRadius = new CornerRadius(14),
        Background = Brushes.Transparent,
        Foreground = AppTheme.Colors.TextSecondary
    };

    private void RefreshNav()
    {
        var colors = AppTheme.Colors;
        StyleTab(_tabHome, _current == "home", colors);
        StyleTab(_tabDownloads, _current == "downloads", colors);
    }

    private static void StyleTab(Button b, bool active, AppColors colors)
    {
        b.Background = active ? SolidColorBrush.Parse("#298B5CF6") : Brushes.Transparent;
        b.Foreground = active ? colors.Purple : colors.TextSecondary;
        b.FontWeight = active ? FontWeight.Bold : FontWeight.Normal;
    }

    // ---------------------------------------------------------------
    // ناوبری
    // ---------------------------------------------------------------

    private void Navigate(string page, bool force = false)
    {
        if (_current == page && !force) return;
        _current = page;
        _pageHost.Content = page switch
        {
            "downloads" => BuildDownloads(),
            "about" => BuildAbout(),
            _ => BuildHome()
        };
        RefreshNav();
    }

    // ---------------------------------------------------------------
    // صفحه خانه
    // ---------------------------------------------------------------

    private Control BuildHome()
    {
        var colors = AppTheme.Colors;
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        var root = new StackPanel { Spacing = 14 };

        // کارت گرادیانی بالای صفحه
        var hero = new Border
        {
            Background = colors.HeroGradient,
            CornerRadius = new CornerRadius(22),
            Padding = new Thickness(24, 20, 24, 20)
        };
        var heroRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 16, VerticalAlignment = VerticalAlignment.Center };
        heroRow.Children.Add(new Border
        {
            Width = 64, Height = 64, CornerRadius = new CornerRadius(18),
            ClipToBounds = true, Child = LogoImage(64)
        });
        var heroText = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        heroText.Children.Add(new TextBlock
        {
            Text = "دانلود با تمام سرعت", FontSize = 21, FontWeight = FontWeight.Bold,
            Foreground = Brushes.White
        });
        heroText.Children.Add(new TextBlock
        {
            Text = "یوتیوب، اینستاگرام، پینترست و هر فایل مستقیم — با ۳۲ ترد همزمان",
            FontSize = 12.5, Foreground = SolidColorBrush.Parse("#D9FFFFFF"),
            TextWrapping = TextWrapping.Wrap
        });
        heroRow.Children.Add(heroText);
        hero.Child = heroRow;
        root.Children.Add(hero);

        // کارت ورودی لینک
        var card = new Border
        {
            Background = colors.Card,
            BorderBrush = colors.CardBorder,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(18),
            Padding = new Thickness(18, 16, 18, 18)
        };
        var cardStack = new StackPanel { Spacing = 12 };

        var row = new Grid
        {
            ColumnDefinitions = { new ColumnDefinition(1, GridUnitType.Star), new ColumnDefinition(GridLength.Auto) }
        };
        _urlBox = new TextBox
        {
            Watermark = "لینک را اینجا بچسبانید…",
            CornerRadius = new CornerRadius(12),
            Background = colors.Track,
            Foreground = colors.TextPrimary,
            BorderBrush = colors.CardBorder,
            Height = 44,
            FontSize = 14,
            VerticalContentAlignment = VerticalAlignment.Center
        };
        _urlBox.PropertyChanged += (_, e) =>
        {
            if (e.Property == TextBox.TextProperty) RefreshDetectChip();
        };
        Grid.SetColumn(_urlBox, 0);
        row.Children.Add(_urlBox);

        var pasteBtn = new Button
        {
            Content = "چسباندن",
            Padding = new Thickness(16, 0, 16, 0),
            Height = 44,
            CornerRadius = new CornerRadius(12),
            Background = colors.Track,
            Foreground = colors.TextPrimary,
            Margin = new Thickness(8, 0, 0, 0),
            VerticalAlignment = VerticalAlignment.Stretch
        };
        pasteBtn.Click += async (_, _) => await PasteClickedAsync();
        Grid.SetColumn(pasteBtn, 1);
        row.Children.Add(pasteBtn);
        cardStack.Children.Add(row);

        // چیپ تشخیص پلتفرم
        _detectChip = new TextBlock
        {
            FontSize = 12.5,
            Foreground = colors.Purple,
            FontWeight = FontWeight.Medium,
            Margin = new Thickness(2, 0, 2, 0)
        };
        cardStack.Children.Add(_detectChip);

        // چیپ‌های کیفیت (وقتی لینک اجتماعی باشد)
        _chipsPanel = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        AddChip("بهترین کیفیت", "auto");
        AddChip("1080p", "1080");
        AddChip("720p", "720");
        AddChip("480p", "480");
        AddChip("فقط صدا", "audio");
        cardStack.Children.Add(_chipsPanel);

        // دکمه شروع
        _startBtn = new Button
        {
            Content = "شروع دانلود",
            Height = 48,
            FontSize = 15.5,
            FontWeight = FontWeight.Bold,
            Foreground = Brushes.White,
            CornerRadius = new CornerRadius(14),
            Background = colors.BrandGradient,
            HorizontalAlignment = HorizontalAlignment.Stretch
        };
        _startBtn.Click += async (_, _) => await StartDownloadAsync();
        cardStack.Children.Add(_startBtn);

        card.Child = cardStack;
        root.Children.Add(card);

        // توضیح کوتاه
        root.Children.Add(new TextBlock
        {
            Text = "فایل‌ها در پوشه Downloads ویندوز ذخیره می‌شوند. بعد از تمام‌شدن می‌توانی پوشه را باز کنی یا نام فایل را عوض کنی.",
            FontSize = 12,
            Foreground = colors.TextSecondary,
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(6, 0, 6, 8)
        });

        RefreshDetectChip();
        RefreshChips();
        scroll.Content = root;
        return scroll;
    }

    private void AddChip(string label, string key)
    {
        var colors = AppTheme.Colors;
        var chip = new Button
        {
            Content = label,
            Padding = new Thickness(14, 7, 14, 7),
            FontSize = 12.5,
            CornerRadius = new CornerRadius(20),
            Tag = key
        };
        chip.Click += (_, _) =>
        {
            if (key == "audio") _audioOnly = !_audioOnly;
            else { _quality = key; _audioOnly = false; }
            RefreshChips();
        };
        _chipsPanel!.Children.Add(chip);
    }

    private void RefreshChips()
    {
        if (_chipsPanel == null) return;
        var colors = AppTheme.Colors;
        foreach (var child in _chipsPanel.Children.OfType<Button>())
        {
            var key = (string)child.Tag!;
            var selected = key == "audio" ? _audioOnly : !_audioOnly && _quality == key;
            child.Background = selected ? colors.BrandGradient : colors.Card;
            child.Foreground = selected
                ? Brushes.White
                : colors.TextSecondary;
            child.FontWeight = selected ? FontWeight.Bold : FontWeight.Normal;
            child.BorderBrush = colors.CardBorder;
        }
    }

    private void RefreshDetectChip()
    {
        if (_detectChip == null || _urlBox == null) return;
        var platform = SocialDetector.Detect(_urlBox.Text);
        var colors = AppTheme.Colors;
        _detectChip.Text = platform switch
        {
            SocialPlatform.Youtube => "لینک یوتیوب شناسایی شد — کیفیت را انتخاب کن",
            SocialPlatform.Instagram => "لینک اینستاگرام شناسایی شد",
            SocialPlatform.Pinterest => "لینک پینترست شناسایی شد",
            _ => ""
        };
        _chipsPanel!.IsVisible = platform != null;
        _detectChip.IsVisible = platform != null;
    }

    private async Task PasteClickedAsync()
    {
        try
        {
            var tl = TopLevel.GetTopLevel(this);
            var text = tl?.Clipboard != null ? await tl.Clipboard.GetTextAsync() : null;
            if (!string.IsNullOrWhiteSpace(text) && _urlBox != null)
            {
                _urlBox.Text = text.Trim();
                _urlBox.CaretIndex = _urlBox.Text?.Length ?? 0;
                ShowStatus("لینک چسبانده شد");
            }
            else ShowStatus("کلیپ‌بورد خالی است", true);
        }
        catch (Exception ex)
        {
            ShowStatus("دسترسی به کلیپ‌بورد ممکن نشد: " + ex.Message, true);
        }
    }

    private async Task StartDownloadAsync()
    {
        var link = _urlBox?.Text?.Trim();
        if (string.IsNullOrEmpty(link))
        {
            ShowStatus("اول لینک را وارد کن", true);
            return;
        }
        var platform = SocialDetector.Detect(link);
        var colors = AppTheme.Colors;
        try
        {
            if (_startBtn != null) _startBtn.IsEnabled = false;
            if (platform != null)
            {
                ShowStatus($"در حال استخراج لینک از {platform.Value.Label()}…");
                var resolved = await SocialResolver.ResolveAsync(link, _quality, _audioOnly, platform.Value);
                var added = _engine.Add(resolved.Url, resolved.FileName, resolved.Mime, true, platform.Value.Label());
                if (added == null) ShowStatus("این دانلود از قبل در جریان است", true);
                else ShowStatus("دانلود اضافه شد — برو به تب دانلودها");
                if (_urlBox != null) _urlBox.Text = "";
            }
            else
            {
                var added = _engine.Add(link);
                if (added == null) ShowStatus("این دانلود از قبل در جریان است", true);
                else ShowStatus("دانلود اضافه شد — برو به تب دانلودها");
                if (_urlBox != null) _urlBox.Text = "";
            }
        }
        catch (SocialException ex)
        {
            ShowStatus(ex.Message, true);
        }
        catch (Exception ex)
        {
            ShowStatus("خطا: " + ex.Message, true);
        }
        finally
        {
            if (_startBtn != null) _startBtn.IsEnabled = true;
        }
    }

    // ---------------------------------------------------------------
    // صفحه دانلودها
    // ---------------------------------------------------------------

    private Control BuildDownloads()
    {
        var colors = AppTheme.Colors;
        var list = new StackPanel { Spacing = 0 };
        list.Children.Add(new TextBlock
        {
            Text = "دانلودها",
            FontSize = 19,
            FontWeight = FontWeight.Bold,
            Foreground = colors.TextPrimary,
            Margin = new Thickness(2, 2, 2, 12)
        });

        if (_rows.Count == 0)
        {
            var empty = new Border
            {
                Background = colors.Card,
                BorderBrush = colors.CardBorder,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(18),
                Padding = new Thickness(30, 36, 30, 36),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 60, 0, 0)
            };
            var es = new StackPanel { Spacing = 8 };
            es.Children.Add(new TextBlock
            {
                Text = "هنوز دانلودی نداری",
                FontSize = 17, FontWeight = FontWeight.Bold,
                Foreground = colors.TextPrimary, TextAlignment = TextAlignment.Center
            });
            es.Children.Add(new TextBlock
            {
                Text = "از تب خانه یک لینک وارد کن تا اینجا ببینی‌اش",
                FontSize = 12.5,
                Foreground = colors.TextSecondary, TextAlignment = TextAlignment.Center
            });
            empty.Child = es;
            list.Children.Add(empty);
        }
        else
        {
            var host = new StackPanel { Spacing = 10 };
            foreach (var row in _rows) host.Children.Add(row);
            list.Children.Add(host);
        }

        return new ScrollViewer
        {
            Content = list,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Padding = new Thickness(0, 0, 0, 10)
        };
    }

    private void RebuildRows()
    {
        _rows.Clear();
        foreach (var item in _engine.Snapshot())
            _rows.Add(new DownloadRow(_engine, item, (msg, err) => ShowStatus(msg, err), this));
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
            var item = _engine.Snapshot().FirstOrDefault(i => i.Id == id);
            if (item == null) return;
            var row = _rows.OfType<DownloadRow>().FirstOrDefault(r => r.Id == id);
            row?.Update(item);
        });
    }

    // ---------------------------------------------------------------
    // صفحه درباره ما
    // ---------------------------------------------------------------

    private Control BuildAbout()
    {
        var colors = AppTheme.Colors;
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        var root = new StackPanel { Spacing = 12, HorizontalAlignment = HorizontalAlignment.Center, MaxWidth = 460 };

        var backRow = new Grid { ColumnDefinitions = { new ColumnDefinition(GridLength.Auto), new ColumnDefinition(1, GridUnitType.Star) } };
        var back = new Button
        {
            Content = "بازگشت",
            Padding = new Thickness(14, 6, 14, 6),
            CornerRadius = new CornerRadius(10),
            Background = colors.Track,
            Foreground = colors.TextPrimary,
            HorizontalAlignment = HorizontalAlignment.Left
        };
        back.Click += (_, _) => Navigate("home");
        Grid.SetColumn(back, 0);
        backRow.Children.Add(back);
        root.Children.Add(backRow);

        root.Children.Add(new Border
        {
            Width = 104, Height = 104, CornerRadius = new CornerRadius(52),
            ClipToBounds = true, Child = LogoImage(104),
            Margin = new Thickness(0, 8, 0, 0)
        });

        root.Children.Add(new TextBlock
        {
            Text = "سهند مرامی",
            FontSize = 22, FontWeight = FontWeight.Bold,
            Foreground = colors.TextPrimary, TextAlignment = TextAlignment.Center
        });
        root.Children.Add(new TextBlock
        {
            Text = "سازنده و توسعه‌دهنده برنامه",
            FontSize = 13,
            Foreground = colors.TextSecondary, TextAlignment = TextAlignment.Center,
            Margin = new Thickness(0, 0, 0, 10)
        });

        // انتخاب تم
        root.Children.Add(new TextBlock
        {
            Text = "تم برنامه",
            FontSize = 14, FontWeight = FontWeight.Bold,
            Foreground = colors.TextPrimary, TextAlignment = TextAlignment.Center
        });
        var segRow = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Center,
            Spacing = 6
        };
        var lightBtn = SegButton("روشن", AppTheme.IsLight);
        var darkBtn = SegButton("تیره", !AppTheme.IsLight);
        lightBtn.Click += (_, _) => AppTheme.SetLight(_appData, true);
        darkBtn.Click += (_, _) => AppTheme.SetLight(_appData, false);
        segRow.Children.Add(lightBtn);
        segRow.Children.Add(darkBtn);
        root.Children.Add(segRow);

        // نسخه
        var ver = new Border
        {
            Background = colors.Card,
            BorderBrush = colors.CardBorder,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(18),
            Padding = new Thickness(18, 13, 18, 13),
            Margin = new Thickness(0, 10, 0, 0)
        };
        var verGrid = new Grid { ColumnDefinitions = { new ColumnDefinition(1, GridUnitType.Star), new ColumnDefinition(GridLength.Auto) } };
        verGrid.Children.Add(new TextBlock
        {
            Text = "نسخه برنامه",
            FontSize = 13,
            Foreground = colors.TextSecondary,
            VerticalAlignment = VerticalAlignment.Center
        });
        var verVal = new TextBlock
        {
            Text = "۱٫۳",
            FontSize = 14, FontWeight = FontWeight.Bold,
            Foreground = colors.TextPrimary
        };
        Grid.SetColumn(verVal, 1);
        verGrid.Children.Add(verVal);
        ver.Child = verGrid;
        root.Children.Add(ver);

        root.Children.Add(new TextBlock
        {
            Text = "طراحی و توسعه با عشق توسط سهند مرامی",
            FontSize = 12,
            Foreground = colors.Purple,
            TextAlignment = TextAlignment.Center,
            Margin = new Thickness(0, 8, 0, 16)
        });

        scroll.Content = root;
        return scroll;
    }

    private static Button SegButton(string label, bool selected)
    {
        var colors = AppTheme.Colors;
        return new Button
        {
            Content = label,
            Padding = new Thickness(26, 9, 26, 9),
            FontSize = 13,
            CornerRadius = new CornerRadius(14),
            Background = selected ? colors.BrandGradient : colors.Track,
            Foreground = selected ? Brushes.White : colors.TextSecondary,
            FontWeight = selected ? FontWeight.Bold : FontWeight.Normal
        };
    }

    private void OnThemeChanged()
    {
        Dispatcher.UIThread.Post(() =>
        {
            BuildUi();
            RebuildRows();
        });
    }

    // ---------------------------------------------------------------
    // اسنک‌بار
    // ---------------------------------------------------------------

    private void ShowStatus(string message, bool isError = false)
    {
        Dispatcher.UIThread.Post(() =>
        {
            var colors = AppTheme.Colors;
            _statusText.Text = message;
            _statusText.Foreground = isError ? colors.AccentRed : colors.AccentGreen;
            _statusTimer?.Stop();
            _statusTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
            _statusTimer.Tick += (_, _) =>
            {
                _statusText.Text = "";
                _statusTimer?.Stop();
            };
            _statusTimer.Start();
        });
    }

    // ---------------------------------------------------------------
    // ردیف دانلود
    // ---------------------------------------------------------------

    private sealed class DownloadRow : Border
    {
        public string Id { get; }
        private readonly DownloadEngine _engine;
        private readonly Action<string, bool> _notify;
        private readonly Window _owner;

        private readonly TextBlock _fileName = new();
        private readonly Border _platformChip = new();
        private readonly TextBlock _platformText = new();
        private readonly TextBlock _stateLine = new();
        private readonly TextBlock _speedLine = new();
        private readonly Border _track = new();
        private readonly Grid _fillGrid = new();
        private readonly ColumnDefinition _fillCol = new(0, GridUnitType.Star);
        private readonly ColumnDefinition _restCol = new(1, GridUnitType.Star);
        private readonly StackPanel _buttons = new();
        private int _lastStatus = -1;

        public DownloadRow(DownloadEngine engine, DownloadItem item, Action<string, bool> notify, Window owner)
        {
            _engine = engine;
            _notify = notify;
            _owner = owner;
            Id = item.Id;
            CornerRadius = new CornerRadius(18);
            Padding = new Thickness(16, 13, 16, 13);
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
                    new RowDefinition(GridLength.Auto),
                    new RowDefinition(GridLength.Auto),
                    new RowDefinition(GridLength.Auto),
                    new RowDefinition(GridLength.Auto)
                }
            };

            // ردیف ۰: نام فایل + پلتفرم
            var top = new Grid
            {
                ColumnDefinitions = { new ColumnDefinition(1, GridUnitType.Star), new ColumnDefinition(GridLength.Auto) }
            };
            _fileName.FontSize = 14;
            _fileName.FontWeight = FontWeight.Bold;
            _fileName.TextTrimming = TextTrimming.CharacterEllipsis;
            _fileName.Foreground = colors.TextPrimary;
            _fileName.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(_fileName, 0);
            top.Children.Add(_fileName);

            _platformText.FontSize = 10.5;
            _platformText.Foreground = colors.Purple;
            _platformChip.Child = _platformText;
            _platformChip.CornerRadius = new CornerRadius(10);
            _platformChip.Padding = new Thickness(9, 3, 9, 3);
            _platformChip.Background = SolidColorBrush.Parse("#1F8B5CF6");
            _platformChip.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(_platformChip, 1);
            top.Children.Add(_platformChip);
            Grid.SetRow(top, 0);
            grid.Children.Add(top);

            // ردیف ۱: نوار پیشرفت
            _fillGrid.ColumnDefinitions.Add(_fillCol);
            _fillGrid.ColumnDefinitions.Add(_restCol);
            _fillGrid.VerticalAlignment = VerticalAlignment.Stretch;
            var fill = new Border
            {
                Background = colors.BrandGradient,
                CornerRadius = new CornerRadius(5),
                HorizontalAlignment = HorizontalAlignment.Stretch
            };
            Grid.SetColumn(fill, 0);
            _fillGrid.Children.Add(fill);
            _track.Child = _fillGrid;
            _track.Height = 10;
            _track.CornerRadius = new CornerRadius(5);
            _track.ClipToBounds = true;
            _track.Background = colors.Track;
            _track.Margin = new Thickness(0, 9, 0, 0);
            Grid.SetRow(_track, 1);
            grid.Children.Add(_track);

            // ردیف ۲: متن وضعیت + سرعت
            var mid = new Grid
            {
                ColumnDefinitions = { new ColumnDefinition(1, GridUnitType.Star), new ColumnDefinition(GridLength.Auto) },
                Margin = new Thickness(0, 7, 0, 0)
            };
            _stateLine.FontSize = 12;
            _stateLine.Foreground = colors.TextSecondary;
            _stateLine.TextTrimming = TextTrimming.CharacterEllipsis;
            Grid.SetColumn(_stateLine, 0);
            mid.Children.Add(_stateLine);
            _speedLine.FontSize = 12;
            _speedLine.Foreground = colors.TextSecondary;
            Grid.SetColumn(_speedLine, 1);
            mid.Children.Add(_speedLine);
            Grid.SetRow(mid, 2);
            grid.Children.Add(mid);

            // ردیف ۳: دکمه‌ها
            _buttons.Orientation = Orientation.Horizontal;
            _buttons.HorizontalAlignment = HorizontalAlignment.Left; // در RTL یعنی سمت شروع متن
            _buttons.Spacing = 8;
            _buttons.Margin = new Thickness(0, 10, 0, 0);
            Grid.SetRow(_buttons, 3);
            grid.Children.Add(_buttons);

            Child = grid;
        }

        public void Update(DownloadItem item)
        {
            var colors = AppTheme.Colors;
            _fileName.Text = item.FileName;
            _platformText.Text = item.Platform ?? "";
            _platformChip.IsVisible = !string.IsNullOrEmpty(item.Platform);

            var pct = item.TotalBytes > 0
                ? Math.Clamp(item.DownloadedBytes * 100.0 / item.TotalBytes, 0, 100) / 100.0
                : 0;
            _fillCol.Width = new GridLength(pct, GridUnitType.Star);
            _restCol.Width = new GridLength(1 - pct, GridUnitType.Star);

            var sizeText = $"{Fmt.FormatBytes(item.DownloadedBytes)} از {Fmt.FormatBytes(item.TotalBytes)}";
            var remaining = item.TotalBytes - item.DownloadedBytes;

            switch (item.Status)
            {
                case DownloadStatus.Queued:
                    _stateLine.Text = "در صف دانلود…";
                    _stateLine.Foreground = colors.TextSecondary;
                    break;
                case DownloadStatus.Connecting:
                    _stateLine.Text = "در حال اتصال به سرور…";
                    _stateLine.Foreground = colors.TextSecondary;
                    break;
                case DownloadStatus.Downloading:
                    _stateLine.Text = $"{Fmt.ToFa((long)Math.Round(pct * 100))}٪ — {sizeText}";
                    _stateLine.Foreground = colors.TextPrimary;
                    _speedLine.Text = $"{Fmt.FormatSpeed(item.SpeedBps)} — {Fmt.EtaText(remaining, item.SpeedBps)}";
                    break;
                case DownloadStatus.Paused:
                    _stateLine.Text = $"متوقف شده — {sizeText}";
                    _stateLine.Foreground = colors.AccentAmber;
                    _speedLine.Text = "";
                    break;
                case DownloadStatus.Completed:
                    _stateLine.Text = $"تکمیل شد — {Fmt.FormatBytes(item.TotalBytes)}";
                    _stateLine.Foreground = colors.AccentGreen;
                    _speedLine.Text = "";
                    break;
                case DownloadStatus.Failed:
                    _stateLine.Text = "خطا: " + (item.ErrorMessage ?? "نامشخص");
                    _stateLine.Foreground = colors.AccentRed;
                    _speedLine.Text = "";
                    break;
            }

            // دکمه‌ها فقط هنگام تغییر وضعیت بازسازی می‌شوند
            if ((int)item.Status != _lastStatus)
            {
                _lastStatus = (int)item.Status;
                RebuildButtons(item, colors);
            }
        }

        private void RebuildButtons(DownloadItem item, AppColors colors)
        {
            _buttons.Children.Clear();
            switch (item.Status)
            {
                case DownloadStatus.Queued:
                case DownloadStatus.Connecting:
                case DownloadStatus.Downloading:
                    _buttons.Children.Add(RowBtn("توقف", colors.Track, colors.TextPrimary, () => _engine.Pause(Id)));
                    _buttons.Children.Add(RowBtn("لغو", colors.Track, colors.AccentRed, () => _engine.Cancel(Id)));
                    break;
                case DownloadStatus.Paused:
                    _buttons.Children.Add(RowBtn("ادامه", colors.BrandGradient, Brushes.White, () => _engine.Resume(Id)));
                    _buttons.Children.Add(RowBtn("تغییر نام", colors.Track, colors.TextPrimary, () => _ = RenameFlowAsync(item)));
                    _buttons.Children.Add(RowBtn("لغو", colors.Track, colors.AccentRed, () => _engine.Cancel(Id)));
                    break;
                case DownloadStatus.Completed:
                    _buttons.Children.Add(RowBtn("باز کردن پوشه", colors.BrandGradient, Brushes.White, OpenFolder));
                    _buttons.Children.Add(RowBtn("تغییر نام", colors.Track, colors.TextPrimary, () => _ = RenameFlowAsync(item)));
                    _buttons.Children.Add(RowBtn("حذف از لیست", colors.Track, colors.TextSecondary, () => _engine.RemoveCompleted(Id)));
                    break;
                case DownloadStatus.Failed:
                    _buttons.Children.Add(RowBtn("تلاش دوباره", colors.BrandGradient, Brushes.White, () => _engine.Resume(Id)));
                    _buttons.Children.Add(RowBtn("تغییر نام", colors.Track, colors.TextPrimary, () => _ = RenameFlowAsync(item)));
                    _buttons.Children.Add(RowBtn("حذف", colors.Track, colors.AccentRed, () => _engine.Cancel(Id)));
                    break;
            }
        }

        private Button RowBtn(string label, IBrush bg, IBrush fg, Action onClick)
        {
            var b = new Button
            {
                Content = label,
                Padding = new Thickness(14, 6, 14, 6),
                FontSize = 12.5,
                CornerRadius = new CornerRadius(12),
                Background = bg,
                Foreground = fg
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
            var currentName = item.FileName;
            var dialog = new Window
            {
                Title = "تغییر نام فایل",
                Width = 420,
                Height = 200,
                WindowStartupLocation = WindowStartupLocation.CenterOwner,
                CanResize = false,
                FontFamily = new FontFamily(FontUri),
                FlowDirection = FlowDirection.RightToLeft
            };
            var colors = AppTheme.Colors;
            var stack = new StackPanel { Margin = new Thickness(20), Spacing = 12 };
            stack.Children.Add(new TextBlock
            {
                Text = "نام جدید فایل را بنویس:",
                FontSize = 14, FontWeight = FontWeight.Bold,
                Foreground = colors.TextPrimary
            });
            var box = new TextBox
            {
                Text = currentName,
                FontSize = 14,
                CornerRadius = new CornerRadius(10),
                Background = colors.Track,
                Foreground = colors.TextPrimary,
                BorderBrush = colors.CardBorder
            };
            stack.Children.Add(box);
            var btnRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, HorizontalAlignment = HorizontalAlignment.Left };
            var ok = new Button
            {
                Content = "ذخیره",
                Padding = new Thickness(24, 8, 24, 8),
                CornerRadius = new CornerRadius(12),
                Background = colors.BrandGradient,
                Foreground = Brushes.White,
                FontWeight = FontWeight.Bold
            };
            var cancel = new Button
            {
                Content = "انصراف",
                Padding = new Thickness(20, 8, 20, 8),
                CornerRadius = new CornerRadius(12),
                Background = colors.Track,
                Foreground = colors.TextPrimary
            };
            var finished = false;
            ok.Click += (_, _) =>
            {
                var err = _engine.Rename(Id, box.Text ?? "");
                if (err != null)
                {
                    _notify(err, true);
                    return;
                }
                finished = true;
                _notify("نام فایل تغییر کرد", false);
                dialog.Close();
            };
            cancel.Click += (_, _) => dialog.Close();
            btnRow.Children.Add(ok);
            btnRow.Children.Add(cancel);
            stack.Children.Add(btnRow);
            dialog.Content = stack;
            dialog.Background = colors.Background;
            dialog.Closed += (_, _) =>
            {
                if (!finished) return;
            };
            await dialog.ShowDialog(_owner);
        }
    }
}
