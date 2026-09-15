package com.sakhand.downloadmanager

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sakhand.downloadmanager.ui.AppRoot
import com.sakhand.downloadmanager.ui.theme.SakhandTheme
import com.sakhand.downloadmanager.ui.theme.ThemeController

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeController.load(this)
        requestNeededPermissions()
        setContent {
            // اپ فارسی است؛ چیدمان همیشه راست‌به‌چپ باشد
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                val isLight by ThemeController.isLight.collectAsStateWithLifecycle()

                // رنگ نوار وضعیت همیشه هماهنگ با تم فعلی باشد
                SideEffect {
                    val style = if (isLight) {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }

                SakhandTheme(isLight = isLight) {
                    AppRoot()
                }
            }
        }
    }

    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 11)
        }
    }
}
