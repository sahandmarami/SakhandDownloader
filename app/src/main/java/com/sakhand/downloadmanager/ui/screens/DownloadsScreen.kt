package com.sakhand.downloadmanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sakhand.downloadmanager.data.DownloadItem
import com.sakhand.downloadmanager.data.DownloadStatus
import com.sakhand.downloadmanager.ui.components.DownloadCard
import com.sakhand.downloadmanager.ui.components.EmptyState
import com.sakhand.downloadmanager.ui.theme.CardDark
import com.sakhand.downloadmanager.ui.theme.Purple
import com.sakhand.downloadmanager.ui.theme.TextSecondary

@Composable
fun DownloadsScreen(items: List<DownloadItem>) {

    var filter by rememberSaveable { mutableStateOf(0) } // 0=همه 1=در جریان 2=تمام‌شده

    val filtered = remember(items, filter) {
        when (filter) {
            1 -> items.filter {
                it.isActive || it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED
            }
            2 -> items.filter { it.status == DownloadStatus.COMPLETED }
            else -> items
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("مدیریت دانلودها", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filter == 0,
                onClick = { filter = 0 },
                label = { Text("همه") },
                colors = chipColors()
            )
            FilterChip(
                selected = filter == 1,
                onClick = { filter = 1 },
                label = { Text("در جریان") },
                colors = chipColors()
            )
            FilterChip(
                selected = filter == 2,
                onClick = { filter = 2 },
                label = { Text("تمام‌شده") },
                colors = chipColors()
            )
        }

        Spacer(Modifier.height(14.dp))

        if (filtered.isEmpty()) {
            EmptyState("هنوز دانلودی اینجا نیست — از صفحه اصلی یک لینک اضافه کن")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered, key = { it.id }) { item ->
                    DownloadCard(item)
                }
            }
        }
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    containerColor = CardDark,
    selectedContainerColor = Purple.copy(alpha = 0.25f),
    labelColor = TextSecondary,
    selectedLabelColor = Color.White
)
