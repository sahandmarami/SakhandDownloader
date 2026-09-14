package com.sakhand.downloadmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sakhand.downloadmanager.data.DownloadItem
import com.sakhand.downloadmanager.data.DownloadStatus
import com.sakhand.downloadmanager.engine.DownloadManager
import com.sakhand.downloadmanager.engine.DownloadService
import com.sakhand.downloadmanager.ui.components.DarkCard
import com.sakhand.downloadmanager.ui.components.DownloadCard
import com.sakhand.downloadmanager.ui.components.GradientButton
import com.sakhand.downloadmanager.ui.components.SectionTitle
import com.sakhand.downloadmanager.ui.components.StatCard
import com.sakhand.downloadmanager.ui.theme.BackgroundDark
import com.sakhand.downloadmanager.ui.theme.BrandGradient
import com.sakhand.downloadmanager.ui.theme.CardBorder
import com.sakhand.downloadmanager.ui.theme.CardDark
import com.sakhand.downloadmanager.ui.theme.Purple
import com.sakhand.downloadmanager.ui.theme.TextPrimary
import com.sakhand.downloadmanager.ui.theme.TextSecondary
import com.sakhand.downloadmanager.util.formatBytes
import com.sakhand.downloadmanager.util.toFa

@Composable
fun HomeScreen(
    items: List<DownloadItem>,
    onMessage: (String) -> Unit,
    onSeeAllDownloads: () -> Unit,
    onOpenSocial: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var url by rememberSaveable { mutableStateOf("") }

    val active = items.filter { it.isActive }
    val totalDownloaded = items.filter { it.status == DownloadStatus.COMPLETED }.sumOf { it.totalBytes }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // هدر گرادیانی
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrandGradient, RoundedCornerShape(26.dp))
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .background(Color.White.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "سخند دانلود منیجر",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "دانلود پرسرعت با ۸ اتصال همزمان",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("دانلود مستقیم از لینک", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("لینک فایل را اینجا بچسبانید…", color = TextSecondary) },
            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null, tint = TextSecondary) },
            trailingIcon = {
                IconButton(onClick = {
                    val text = clipboard.getText()?.toString()
                    if (!text.isNullOrBlank()) url = text.trim()
                }) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = "چسباندن", tint = TextSecondary)
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Purple,
                unfocusedBorderColor = CardBorder,
                focusedContainerColor = CardDark,
                unfocusedContainerColor = CardDark,
                cursorColor = Purple,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )

        Spacer(Modifier.height(12.dp))
        GradientButton(text = "شروع دانلود") {
            val id = DownloadManager.addDownload(url.trim())
            if (id == null) {
                onMessage("این لینک هم‌اکنون در صف دانلود است")
            } else {
                DownloadService.start(context)
                onMessage("دانلود شروع شد")
                url = ""
            }
        }

        if (active.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            SectionTitle("در حال دانلود") {
                TextButton(onClick = onSeeAllDownloads) {
                    Text("مشاهده همه", color = Purple)
                }
            }
            active.take(3).forEach { item ->
                Spacer(Modifier.height(10.dp))
                DownloadCard(item, compact = true)
            }
        }

        Spacer(Modifier.height(24.dp))
        Row {
            StatCard(
                icon = Icons.Rounded.VideoLibrary,
                value = toFa(items.size.toLong()),
                label = "کل دانلودها",
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            StatCard(
                icon = Icons.Rounded.Storage,
                value = formatBytes(totalDownloaded),
                label = "حجم دریافتی",
                modifier = Modifier.weight(1.3f)
            )
            Spacer(Modifier.width(10.dp))
            StatCard(
                icon = Icons.Rounded.Bolt,
                value = toFa(active.size.toLong()),
                label = "فعال",
                modifier = Modifier.weight(0.8f)
            )
        }

        Spacer(Modifier.height(18.dp))
        DarkCard(onClick = onOpenSocial) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Purple.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.SmartDisplay, contentDescription = null, tint = Purple)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "دانلود ویدیوی شبکه‌های اجتماعی",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "یوتیوب، اینستاگرام و پینترست",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = TextSecondary
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "فایل‌های دانلودشده در پوشه Download حافظه ذخیره می‌شوند",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
