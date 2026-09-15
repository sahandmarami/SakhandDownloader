using System;
using System.IO;
using System.Linq;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Media;
using Avalonia.Themes.Fluent;
using Avalonia.Threading;
using SakhandDm.Core;
using SakhandDm.Ui;

namespace SakhandDm;

public static class Program
{
    public const string FontUri = "avares://SakhandDm/Assets/Fonts/#Vazirmatn";

    [STAThread]
    public static int Main(string[] args)
    {
        // تست دودی موتور بدون رابط گرافیکی (برای محیط بیلد)
        if (args.Contains("--engine-test"))
        {
            return SelfTest.RunAsync().GetAwaiter().GetResult();
        }

        try
        {
            return BuildAvaloniaApp()
                .StartWithClassicDesktopLifetime(args);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine(ex);
            return 1;
        }
    }

    public static AppBuilder BuildAvaloniaApp()
        => AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .With(new Win32PlatformOptions())
            .LogToTrace();
}

public class App : Application
{
    public static DownloadEngine? Engine { get; private set; }

    public override void Initialize()
    {
        // رابط کاملاً با کد C#‎ ساخته میشود — بدون XAML
    }

    public override void OnFrameworkInitializationCompleted()
    {
        Styles.Add(new FluentTheme());
        RequestedThemeVariant = global::Avalonia.Styling.ThemeVariant.Dark;

        var desktop = ApplicationLifetime as IClassicDesktopStyleApplicationLifetime;
        var args = desktop?.Args ?? Array.Empty<string>();
        var si = Array.IndexOf(args, "--snap");
        var snap = si >= 0;
        var snapDir = snap && si + 1 < args.Length
            ? args[si + 1]
            : Path.Combine(Environment.CurrentDirectory, "snap");

        var appData = snap
            ? Path.Combine(Path.GetTempPath(), "sakhand_snap")
            : DownloadEngine.DefaultAppDataDir();

        if (snap)
        {
            try { Directory.CreateDirectory(appData); } catch { }
            AppTheme.SetLight(appData, false); // شروع تور با تم تیره
            MainWindow.SnapDemo = true;
        }
        else
        {
            AppTheme.Load(appData);
        }

        Engine = new DownloadEngine(appData);
        if (!snap) Engine.LoadPersisted();
        Engine.StartSpeedSampler();

        if (desktop != null)
        {
            var win = new MainWindow(Engine);
            desktop.MainWindow = win;

            if (snap)
            {
                win.Show();
                MainWindow.RunSnapTour(win, snapDir);
                return;
            }

            var confirmed = false;
            desktop.ShutdownRequested += (_, e) =>
            {
                if (confirmed) return; // دفعه دوم اجازه بده
                // دانلودهای فعال برای خروج موقتاً متوقف میشوند و وضعیت ذخیره میشود
                e.Cancel = true;
                confirmed = true;
                Engine.PauseAllForExit();
                Dispatcher.UIThread.InvokeAsync(() => desktop.Shutdown(), DispatcherPriority.Background);
            };
        }

        base.OnFrameworkInitializationCompleted();
    }
}
