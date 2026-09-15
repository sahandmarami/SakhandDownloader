using System;
using System.IO;
using System.Threading.Tasks;

namespace SakhandDm.Core;

/// <summary>تست دودی موتور دانلود — بدون رابط گرافیکی (برای محیط بیلد)</summary>
public static class SelfTest
{
    public static async Task<int> RunAsync()
    {
        Console.WriteLine("=== DownloadEngine self-test ===");
        var tempRoot = Path.Combine(Path.GetTempPath(), "sakhand_dm_selftest_" + Guid.NewGuid().ToString("N")[..8]);
        Directory.CreateDirectory(tempRoot);

        try
        {
            var engine = new DownloadEngine(tempRoot);
            var done = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            var lastPct = -1;

            engine.ItemsChanged += () => { };
            engine.ItemUpdated += _ =>
            {
                var item = engine.Snapshot().Find(i => true);
                if (item == null) return;
                var pct = item.TotalBytes > 0 ? (int)(item.DownloadedBytes * 100 / item.TotalBytes) : 0;
                if (pct != lastPct)
                {
                    lastPct = pct;
                    Console.WriteLine($"  progress: {pct}%  speed: {Fmt.FormatSpeed(item.SpeedBps)}");
                }
                if (item.Status == DownloadStatus.Completed) done.TrySetResult(true);
                if (item.Status == DownloadStatus.Failed) done.TrySetResult(false);
            };

            // فایل چند مگابایتی با پشتیبانی Range برای تست چندتردی
            const string url = "https://proof.ovh.net/files/1Mb.dat";
            Console.WriteLine("adding: " + url);
            var id = engine.Add(url, "test_1mb.dat");
            if (id == null)
            {
                Console.WriteLine("FAILED: engine refused to add");
                return 1;
            }

            var winner = await Task.WhenAny(done.Task, Task.Delay(TimeSpan.FromSeconds(90)));
            if (winner != done.Task || !done.Task.Result)
            {
                Console.WriteLine("FAILED: download did not complete in time");
                return 1;
            }

            var item2 = engine.Snapshot()[0];
            var path = item2.SavedPath;
            if (path == null || !File.Exists(path))
            {
                Console.WriteLine("FAILED: saved file missing");
                return 1;
            }
            var len = new FileInfo(path).Length;
            Console.WriteLine($"saved: {path} ({len} bytes)");
            if (len != 1024 * 1024)
            {
                Console.WriteLine("FAILED: unexpected size");
                return 1;
            }
            File.Delete(path);
            Console.WriteLine("=== SELF TEST PASSED ===");
            return 0;
        }
        catch (Exception ex)
        {
            Console.WriteLine("FAILED: " + ex);
            return 1;
        }
        finally
        {
            try { Directory.Delete(tempRoot, recursive: true); } catch { }
        }
    }
}
