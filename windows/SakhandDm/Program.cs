using System;
using System.Linq;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Avalonia.Themes.Fluent;
using Avalonia.Threading;
using SakhandDm.Core;
using SakhandDm.Ui;

namespace SakhandDm;

public static class Program
{
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
        // رابط کاملاً با کد C# ساخته می‌شود — بدون XAML
    }

    public override void OnFrameworkInitializationCompleted()
    {
        Styles.Add(new FluentTheme());
        RequestedThemeVariant = global::Avalonia.Styling.ThemeVariant.Dark;

        var appData = DownloadEngine.DefaultAppDataDir();
        AppTheme.Load(appData);
        Engine = new DownloadEngine(appData);
        Engine.LoadPersisted();
        Engine.StartSpeedSampler();

        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.MainWindow = new MainWindow(Engine);

            var confirmed = false;
            desktop.ShutdownRequested += (_, e) =>
            {
                if (confirmed) return; // دفعه دوم اجازه بده
                // دانلودهای فعال برای خروج موقتاً متوقف می‌شوند و وضعیت ذخیره می‌شود
                e.Cancel = true;
                confirmed = true;
                Engine.PauseAllForExit();
                Dispatcher.UIThread.InvokeAsync(() => desktop.Shutdown(), DispatcherPriority.Background);
            };
        }

        base.OnFrameworkInitializationCompleted();
    }
}
