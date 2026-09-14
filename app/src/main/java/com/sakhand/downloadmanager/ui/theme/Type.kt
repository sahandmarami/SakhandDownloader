package com.sakhand.downloadmanager.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.sakhand.downloadmanager.R

/**
 * تایپوگرافی اپ با فونت فارسی وزیرمتن
 */
val AppFont = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
    Font(R.font.vazirmatn_bold, FontWeight.SemiBold)
)

private fun style(size: androidx.compose.ui.unit.TextUnit, weight: FontWeight, lineHeight: androidx.compose.ui.unit.TextUnit) =
    TextStyle(
        fontFamily = AppFont,
        fontSize = size,
        fontWeight = weight,
        lineHeight = lineHeight
    )

val AppTypography = Typography(
    displayLarge = style(57.sp, FontWeight.Bold, 64.sp),
    displayMedium = style(45.sp, FontWeight.Bold, 52.sp),
    displaySmall = style(36.sp, FontWeight.Bold, 44.sp),
    headlineLarge = style(32.sp, FontWeight.Bold, 40.sp),
    headlineMedium = style(28.sp, FontWeight.Bold, 36.sp),
    headlineSmall = style(24.sp, FontWeight.Bold, 32.sp),
    titleLarge = style(22.sp, FontWeight.Bold, 28.sp),
    titleMedium = style(16.sp, FontWeight.SemiBold, 24.sp),
    titleSmall = style(14.sp, FontWeight.Medium, 20.sp),
    bodyLarge = style(16.sp, FontWeight.Normal, 26.sp),
    bodyMedium = style(14.sp, FontWeight.Normal, 24.sp),
    bodySmall = style(12.sp, FontWeight.Normal, 20.sp),
    labelLarge = style(14.sp, FontWeight.Medium, 20.sp),
    labelMedium = style(12.sp, FontWeight.Medium, 16.sp),
    labelSmall = style(11.sp, FontWeight.Medium, 16.sp)
)
