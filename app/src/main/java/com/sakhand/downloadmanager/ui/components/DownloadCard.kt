package com.sakhand.downloadmanager.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sakhand.downloadmanager.data.DownloadItem
import com.sakhand.downloadmanager.data.DownloadStatus
import com.sakhand.downloadmanager.data.SocialPlatform
import com.sakhand.downloadmanager.engine.DownloadManager
import com.sakhand.downloadmanager.engine.StorageSaver
import com.sakhand.downloadmanager.ui.theme.AccentAmber
import com.sakhand.downloadmanager.ui.theme.AccentGreen
import com.sakhand.downloadmanager.ui.theme.AccentRed
import com.sakhand.downloadmanager.ui.theme.Blue
import com.sakhand.downloadmanager.ui.theme.CardDark
import com.sakhand.downloadmanager.ui.theme.Purple
import com.sakhand.downloadmanager.ui.theme.TextSecondary
import com.sakhand.downloadmanager.ui.theme.TrackGray
import com.sakhand.downloadmanager.util.formatBytes
import com.sakhand.downloadmanager.util.formatSpeed
import com.sakhand.downloadmanager.util.toFa

/**
 * کارت نمایش دانلود — هم در صفحه اصلی (کوچک) و هم در صفحه دانلودها استفاده می‌شود
 */
@Composable
fun DownloadCard(item: DownloadItem, compact: Boolean = false) {
    val context = LocalContext.current

    val progress = if (item.totalBytes > 0) {
        (item.downloadedBytes.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(350),
        label = "dlProgress"
    )

    val color: Color = when (item.status) {
        DownloadStatus.DOWNLOADING -> Blue
        DownloadStatus.CONNECTING, DownloadStatus.QUEUED -> Purple
        DownloadStatus.PAUSED -> AccentAmber
        DownloadStatus.COMPLETED -> AccentGreen
        DownloadStatus.FAILED -> AccentRed
        DownloadStatus.CANCELED -> TextSecondary
    }
    val icon: ImageVector = when (item.status) {
        DownloadStatus.DOWNLOADING -> Icons.Rounded.Downloading
        DownloadStatus.CONNECTING, DownloadStatus.QUEUED -> Icons.Rounded.CloudDownload
        DownloadStatus.PAUSED -> Icons.Rounded.PauseCircle
        DownloadStatus.COMPLETED -> Icons.Rounded.CheckCircle
        DownloadStatus.FAILED -> Icons.Rounded.ErrorOutline
        DownloadStatus.CANCELED -> Icons.Rounded.Close
    }

    val subtitle = when (item.status) {
        DownloadStatus.CONNECTING -> "در حال برقراری اتصال…"
        DownloadStatus.QUEUED -> "در صف دانلود"
        DownloadStatus.DOWNLOADING -> buildString {
            if (item.totalBytes > 0) {
                append("${formatBytes(item.downloadedBytes)} از ${formatBytes(item.totalBytes)}")
            } else {
                append(formatBytes(item.downloadedBytes))
            }
            append(" • ${formatSpeed(item.speedBps)}")
        }
        DownloadStatus.PAUSED -> "متوقف شد • ${formatBytes(item.downloadedBytes)} دریافت شده"
        DownloadStatus.COMPLETED -> "تکمیل شد • ${formatBytes(item.totalBytes)}"
        DownloadStatus.FAILED -> "دانلود ناموفق بود"
        DownloadStatus.CANCELED -> "لغو شد"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardDark, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(color.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val platformLabel = item.platform?.let {
                    runCatching { SocialPlatform.valueOf(it) }.getOrNull()?.label
                }
                Text(
                    if (item.isSocial && platformLabel != null) "$subtitle — از $platformLabel"
                    else subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row {
                when (item.status) {
                    DownloadStatus.DOWNLOADING,
                    DownloadStatus.CONNECTING,
                    DownloadStatus.QUEUED -> {
                        ActionIcon(Icons.Rounded.Pause, "توقف") { DownloadManager.pause(item.id) }
                        ActionIcon(Icons.Rounded.Close, "لغو") { DownloadManager.cancel(item.id) }
                    }
                    DownloadStatus.PAUSED -> {
                        ActionIcon(Icons.Rounded.PlayArrow, "ادامه") { DownloadManager.resume(item.id) }
                        ActionIcon(Icons.Rounded.Close, "لغو") { DownloadManager.cancel(item.id) }
                    }
                    DownloadStatus.COMPLETED -> {
                        ActionIcon(Icons.Rounded.OpenInNew, "باز کردن") {
                            StorageSaver.openFile(context, item)
                        }
                        ActionIcon(Icons.Rounded.DeleteOutline, "حذف") {
                            DownloadManager.removeCompleted(item.id)
                        }
                    }
                    DownloadStatus.FAILED -> {
                        ActionIcon(Icons.Rounded.Refresh, "تلاش دوباره") {
                            DownloadManager.resume(item.id)
                        }
                        ActionIcon(Icons.Rounded.Close, "حذف") {
                            DownloadManager.cancel(item.id)
                        }
                    }
                    DownloadStatus.CANCELED -> Unit
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (item.status == DownloadStatus.CONNECTING ||
            item.status == DownloadStatus.QUEUED ||
            (item.status == DownloadStatus.DOWNLOADING && item.totalBytes <= 0L)
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(6.dp)),
                color = color,
                trackColor = TrackGray
            )
        } else {
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(6.dp)),
                color = color,
                trackColor = TrackGray
            )
        }

        if (!compact && item.status == DownloadStatus.DOWNLOADING && item.totalBytes > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "${toFa((animated * 100).toInt())}٪",
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }

        if (!compact && item.status == DownloadStatus.FAILED && !item.errorMessage.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                item.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = AccentRed
            )
        }
    }
}

@Composable
private fun ActionIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = TextSecondary,
            modifier = Modifier.size(22.dp)
        )
    }
}
