package com.sakhand.downloadmanager

import android.app.Application
import com.sakhand.downloadmanager.engine.DownloadManager

/**
 * کلاس اپلیکیشن — موتور دانلود اینجا مقداردهی اولیه می‌شود
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        DownloadManager.init(this)
    }
}
