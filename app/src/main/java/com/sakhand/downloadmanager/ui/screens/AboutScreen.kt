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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sakhand.downloadmanager.R
import com.sakhand.downloadmanager.ui.theme.AppTheme
import com.sakhand.downloadmanager.ui.theme.BrandGradient
import com.sakhand.downloadmanager.ui.theme.Purple
import com.sakhand.downloadmanager.ui.theme.ThemeController
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    val isLight by ThemeController.isLight.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "بازگشت",
                    tint = colors.textPrimary
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // آیکون برنامه
        Box(
            modifier = Modifier
                .size(104.dp)
                .background(
                    Brush.linearGradient(listOf(Purple.copy(alpha = 0.25f), Color(0xFF3B82F6).copy(alpha = 0.25f))),
                    CircleShape
                )
                .padding(6.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_app_logo),
                contentDescription = "آیکون برنامه",
                tint = Color.Unspecified,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        }

        Spacer(Modifier.height(14.dp))
        Text("سهند مرامی", style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
        Text(
            "سازنده و توسعه‌دهنده برنامه",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )

        Spacer(Modifier.height(24.dp))

        // انتخاب تم
        Text("تم برنامه", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        Spacer(Modifier.height(10.dp))
        ThemeToggle(
            isLight = isLight,
            onSelect = { light -> ThemeController.setLight(context, light) }
        )

        Spacer(Modifier.height(20.dp))

        // نسخه
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colors.card)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("نسخه برنامه", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            Spacer(Modifier.weight(1f))
            Text(
                "۱٫۲",
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "طراحی و توسعه با عشق توسط سهند مرامی",
            style = MaterialTheme.typography.bodySmall,
            color = Purple
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * کلید انتخاب تم — دو حالته روشن / تیره
 */
@Composable
private fun ThemeToggle(
    isLight: Boolean,
    onSelect: (Boolean) -> Unit
) {
    val colors = AppTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.track)
            .padding(5.dp)
    ) {
        ToggleOption(
            icon = Icons.Rounded.LightMode,
            label = "روشن",
            selected = isLight,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(true) }
        )
        ToggleOption(
            icon = Icons.Rounded.DarkMode,
            label = "تیره",
            selected = !isLight,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(false) }
        )
    }
}

@Composable
private fun ToggleOption(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) BrandGradient else SolidColor(Color.Transparent)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) Color.White else colors.textSecondary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) Color.White else colors.textSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
