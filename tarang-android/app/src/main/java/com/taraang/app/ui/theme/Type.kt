package com.taraang.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 차량 화면 기준(ARCHITECTURE.md 8장): 모든 글자 24sp 이상. 가장 작은 스타일(bodySmall·label*)도 24sp이고,
 * 위계는 크기 대신 굵기·색(Graphite2)으로 준다. TypographyTest가 지킨다.
 */
val TaraangTypography = Typography(
    displayLarge = TextStyle(fontSize = 64.sp, lineHeight = 76.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontSize = 52.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontSize = 44.sp, lineHeight = 54.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 38.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 26.sp, lineHeight = 36.sp),
    bodyMedium = TextStyle(fontSize = 24.sp, lineHeight = 34.sp),
    bodySmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
    labelLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
)
