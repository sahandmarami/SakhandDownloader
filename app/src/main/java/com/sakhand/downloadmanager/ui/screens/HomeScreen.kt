package com.sakhand.downloadmanager.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sakhand.downloadmanager.data.DownloadItem
import com.sakhand.downloadmanager.engine.DownloadManager
import com.sakhand.downloadmanager.engine.DownloadService
import com.sakhand.downloadmanager.social.SocialDetector
import com.sakhand.downloadmanager.social.SocialResolver
import com.sakhand.downloadmanager.ui.components.DownloadCard
import com.sakhand.downloadmanager.ui.components.GradientButton
import com.sakhand.downloadmanager.ui.components.SectionTitle
import com.sakhand.downloadmanager.ui.theme.AccentGreen
import com.sakhand.downloadmanager.ui.theme.AppTheme
import com.sakhand.downloadmanager.ui.theme.Blue
import com.sakhand.downloadmanager.ui.theme.BrandGradientColors
import com.sakhand.downloadmanager.ui.theme.Purple
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    items: List<DownloadItem>,
    onMessage: (String) -> Unit,
    onSeeAllDownloads: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val colors = AppTheme.colors

    var url by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("1080") } // max / 1080 / 720 / 480 / audio
    var loading by rememberSaveable { mutableStateOf(false) }

    // تشخیص خودکار: اگر لینک شبکه اجتماعی بود، گزینه‌های کیفیت ظاهر می‌شوند
    val platform = remember(url) { SocialDetector.detect(url) }
    val active = items.filter { it.isActive }

    // گرادیان متحرک هدر — درخشش ملایم و همیشگی
    val shimmer = rememberInfiniteTransition(label = "headerGlow")
    val shift by shimmer.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "headerShift"
    )
    val headerBrush = Brush.linearGradient(
        colors = BrandGradientColors + Blue.copy(alpha = 0.92f),
        start = Offset(x = shift * 460f - 140f, y = shift * 120f),
        end = Offset(x = shift * 460f + 320f, y = 420f - shift * 120f)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // هدر گرادیانی متحرک
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(headerBrush)
                .padding(horizontal = 18.dp, vertical = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Download Manager",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Text(
                        "دانلود سریع فایل و ویدیو",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.88f)
                    )
                }
                IconButton(onClick = onOpenAbout) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = "درباره ما",
                        tint = Color.White
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("لینک را اینجا بچسبانید…", color = colors.textSecondary) },
            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null, tint = colors.textSecondary) },
            trailingIcon = {
                IconButton(onClick = {
                    val text = clipboard.getText()?.toString()
                    if (!text.isNullOrBlank()) url = text.trim()
                }) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = "چسباندن", tint = colors.textSecondary)
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Purple,
                unfocusedBorderColor = colors.cardBorder,
                focusedContainerColor = colors.card,
                unfocusedContainerColor = colors.card,
                cursorColor = Purple,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary
            )
        )

        if (platform != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = AccentGreen,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "لینک ${platform.label} شناسایی شد",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentGreen
                )
            }

            Spacer(Modifier.height(10.dp))
            Text("کیفیت", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "max" to "بهترین",
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
                            containerColor = colors.card,
                            selectedContainerColor = Purple.copy(alpha = 0.3f),
                            labelColor = colors.textSecondary,
                            selectedLabelColor = if (colors.isDark) Color.White else Color(0xFF2B1B57)
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        GradientButton(
            text = if (loading) "لطفاً صبر کن…" else if (platform != null) "استخراج و دانلود" else "شروع دانلود",
            enabled = url.isNotBlank() && !loading
        ) {
            val detected = SocialDetector.detect(url)
            if (detected == null) {
                // دانلود مستقیم از سایت و هر لینک معمولی
                val id = DownloadManager.addDownload(url.trim())
                if (id == null) {
                    onMessage("این لینک هم‌اکنون در صف دانلود است")
                } else {
                    DownloadService.start(context)
                    onMessage("دانلود شروع شد")
                    url = ""
                }
            } else {
                // استخراج لینک رسانه و سپس دانلود
                loading = true
                scope.launch {
                    try {
                        val resolved = SocialResolver.resolve(
                            link = url.trim(),
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
                            url = ""
                        }
                    } catch (e: Exception) {
                        onMessage(e.message ?: "خطای ناشناخته در دریافت لینک")
                    } finally {
                        loading = false
                    }
                }
            }
        }

        if (loading && platform != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = Purple,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "در حال استخراج لینک رسانه…",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }

        if (active.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            SectionTitle("در حال دانلود") {
                TextButton(onClick = onSeeAllDownloads) {
                    Text("همه", color = Purple)
                }
            }
            active.take(3).forEach { item ->
                Spacer(Modifier.height(10.dp))
                DownloadCard(item, compact = true)
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "لینک‌های یوتیوب، اینستاگرام و پینترست خودکار شناسایی می‌شوند — فایل‌ها در پوشه Download ذخیره می‌شوند",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
