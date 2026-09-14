package com.sakhand.downloadmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sakhand.downloadmanager.engine.DownloadManager
import com.sakhand.downloadmanager.engine.DownloadService
import com.sakhand.downloadmanager.social.SocialDetector
import com.sakhand.downloadmanager.social.SocialResolver
import com.sakhand.downloadmanager.ui.components.DarkCard
import com.sakhand.downloadmanager.ui.components.GradientButton
import com.sakhand.downloadmanager.ui.theme.AccentGreen
import com.sakhand.downloadmanager.ui.theme.AccentRed
import com.sakhand.downloadmanager.ui.theme.Blue
import com.sakhand.downloadmanager.ui.theme.BrandGradient
import com.sakhand.downloadmanager.ui.theme.CardBorder
import com.sakhand.downloadmanager.ui.theme.CardDark
import com.sakhand.downloadmanager.ui.theme.Pink
import com.sakhand.downloadmanager.ui.theme.Purple
import com.sakhand.downloadmanager.ui.theme.TextPrimary
import com.sakhand.downloadmanager.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SocialScreen(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var link by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("1080") } // max / 1080 / 720 / 480 / audio
    var loading by rememberSaveable { mutableStateOf(false) }

    val platform = remember(link) { SocialDetector.detect(link) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // هدر گرادیانی کوچک
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrandGradient, RoundedCornerShape(22.dp))
                .padding(18.dp)
        ) {
            Column {
                Text(
                    "دانلود از شبکه‌های اجتماعی",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "لینک بده، ویدیو را با کیفیت دلخواه بگیر",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("مثلاً https://youtu.be/…", color = TextSecondary) },
            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null, tint = TextSecondary) },
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

        Spacer(Modifier.height(10.dp))

        if (platform != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = AccentGreen,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "لینک ${platform.label} شناسایی شد",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentGreen
                )
            }
        } else {
            Text(
                "لینک یوتیوب، اینستاگرام یا پینترست را وارد کن",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("کیفیت", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "max" to "بهترین کیفیت",
                "1080" to "1080p",
                "720" to "720p",
                "480" to "480p",
                "audio" to "فقط صدا"
            ).forEach { (value, label) ->
                FilterChip(
                    selected = mode == value,
                    onClick = { mode = value },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = CardDark,
                        selectedContainerColor = Purple.copy(alpha = 0.3f),
                        labelColor = TextSecondary,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        GradientButton(
            text = if (loading) "لطفاً صبر کن…" else "دانلود",
            enabled = link.isNotBlank() && !loading
        ) {
            val detected = SocialDetector.detect(link)
            if (detected == null) {
                onMessage("این لینک جزو سرویس‌های پشتیبانی‌شده نیست")
                return@GradientButton
            }
            loading = true
            scope.launch {
                try {
                    val resolved = SocialResolver.resolve(
                        link = link.trim(),
                        quality = mode,
                        audioOnly = mode == "audio",
                        platform = detected
                    )
                    val id = DownloadManager.addDownload(
                        url = resolved.url,
                        fileName = resolved.fileName,
                        mime = resolved.mime,
                        isSocial = true,
                        platform = detected
                    )
                    if (id == null) {
                        onMessage("این فایل هم‌اکنون در صف دانلود است")
                    } else {
                        DownloadService.start(context)
                        onMessage("دانلود شروع شد")
                        link = ""
                    }
                } catch (e: Exception) {
                    onMessage(e.message ?: "خطای ناشناخته در دریافت لینک")
                } finally {
                    loading = false
                }
            }
        }

        if (loading) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = Purple,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "در حال استخراج لینک رسانه…",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        Spacer(Modifier.height(26.dp))
        Text("پلتفرم‌های پشتیبانی‌شده", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))

        PlatformCard(
            icon = Icons.Rounded.SmartDisplay,
            color = Blue,
            title = "یوتیوب",
            subtitle = "ویدیو تا بالاترین کیفیت + استخراج فایل صوتی"
        )
        Spacer(Modifier.height(10.dp))
        PlatformCard(
            icon = Icons.Rounded.PhotoCamera,
            color = Pink,
            title = "اینستاگرام",
            subtitle = "ریلز، پست و IGTV از حساب‌های عمومی"
        )
        Spacer(Modifier.height(10.dp))
        PlatformCard(
            icon = Icons.Rounded.Image,
            color = AccentRed,
            title = "پینترست",
            subtitle = "عکس‌ها و ویدیوهای پین به‌صورت مستقیم و بدون محدودیت"
        )

        Spacer(Modifier.height(16.dp))
        DarkCard {
            Text(
                "نکته: پینترست مستقیم و بدون واسطه دانلود می‌شود. برای یوتیوب و اینستاگرام از یک سرویس استخراج استفاده می‌شود که طبق راهنمای README قابل تنظیم یا شخصی‌سازی است.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PlatformCard(
    icon: ImageVector,
    color: Color,
    title: String,
    subtitle: String
) {
    DarkCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(color.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}
