package com.sakhand.downloadmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sakhand.downloadmanager.ui.theme.AccentGreen
import com.sakhand.downloadmanager.ui.theme.BrandGradientColors
import com.sakhand.downloadmanager.ui.theme.CardDark
import com.sakhand.downloadmanager.ui.theme.Purple
import com.sakhand.downloadmanager.ui.theme.TextPrimary
import com.sakhand.downloadmanager.ui.theme.TextSecondary
import com.sakhand.downloadmanager.util.toFa

@Composable
fun AboutScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(18.dp))

        // آواتار سازنده با گرادیان برند
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(Brush.linearGradient(BrandGradientColors), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "س",
                style = MaterialTheme.typography.displayMedium,
                color = TextPrimary
            )
        }

        Spacer(Modifier.height(14.dp))
        Text("سخند مرامی", style = MaterialTheme.typography.headlineMedium)
        Text(
            "سازنده و توسعه‌دهنده اپلیکیشن",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(Modifier.height(22.dp))

        AboutCard(title = "درباره برنامه") {
            Text(
                "سخند دانلود منیجر یک اپلیکیشن دانلود پرسرعت برای اندروید است که با موتور چندتردی ۸ کاناله، فایل‌ها را چند برابر سریع‌تر از دانلود معمولی مرورگر دریافت می‌کند. توقف و ادامه دانلود حتی بعد از بستن برنامه، اطلاع‌رسانی زنده پیشرفت با نمایش سرعت لحظه‌ای، رابط کاربری کاملاً فارسی با تم تیره مدرن و بخش ویژه دانلود ویدیو از یوتیوب، اینستاگرام و پینترست، تجربه‌ای روان و حرفه‌ای برای شما ساخته است.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Justify
            )
        }

        Spacer(Modifier.height(12.dp))

        AboutCard(title = "ویژگی‌ها") {
            FeatureRow(Icons.Rounded.Bolt, "شتاب‌دهی دانلود با ۸ اتصال همزمان")
            FeatureRow(Icons.Rounded.CheckCircle, "توقف و ادامه دانلود در هر لحظه")
            FeatureRow(Icons.Rounded.SmartDisplay, "دانلود ویدیو از یوتیوب، اینستاگرام و پینترست")
            FeatureRow(Icons.Rounded.DesignServices, "رابط کاربری فارسی، راست‌چین و تم تیره")
            FeatureRow(Icons.Rounded.Code, "ساخته‌شده با Kotlin و Jetpack Compose")
        }

        Spacer(Modifier.height(12.dp))

        AboutCard(title = "اطلاعات نسخه") {
            InfoRow("نسخه", "۱٫۰٫۰")
            InfoRow("حداقل اندروید", "۸٫۰ (API ${toFa(26)})")
            InfoRow("موتور دانلود", "چندتردی با OkHttp")
            InfoRow("رابط کاربری", "Jetpack Compose — Material 3")
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "طراحی و توسعه با عشق توسط سخند مرامی",
            style = MaterialTheme.typography.bodySmall,
            color = Purple
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardDark, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = null,
                tint = Purple,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = AccentGreen,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
