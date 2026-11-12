package com.arc.nupcar.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.arc.nupcar.R

private val DefaultTypography = Typography()

val AppTypography = DefaultTypography.copy(
    headlineLarge = DefaultTypography.headlineLarge.copy(fontSize = 38.sp, lineHeight = 46.sp),
    headlineMedium = DefaultTypography.headlineMedium.copy(fontSize = 34.sp, lineHeight = 42.sp),
    headlineSmall = DefaultTypography.headlineSmall.copy(fontSize = 30.sp, lineHeight = 38.sp),
    titleLarge = DefaultTypography.titleLarge.copy(fontSize = 26.sp, lineHeight = 34.sp),
    titleMedium = DefaultTypography.titleMedium.copy(fontSize = 22.sp, lineHeight = 30.sp),
    titleSmall = DefaultTypography.titleSmall.copy(fontSize = 20.sp, lineHeight = 26.sp),
    bodyLarge = DefaultTypography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 30.sp),
    bodyMedium = DefaultTypography.bodyMedium.copy(fontSize = 20.sp, lineHeight = 28.sp),
    bodySmall = DefaultTypography.bodySmall.copy(fontSize = 18.sp, lineHeight = 26.sp),
    labelLarge = DefaultTypography.labelLarge.copy(fontSize = 20.sp, lineHeight = 26.sp),
    labelMedium = DefaultTypography.labelMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
    labelSmall = DefaultTypography.labelSmall.copy(fontSize = 18.sp, lineHeight = 24.sp)
)

private val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_semibold, FontWeight.Bold),
)

/** Nupcar screen: Pretendard, a touch heavier than Regular, with roomier lines. */
val NupcarTypography = with(Typography()) {
    fun TextStyle.soft(weight: FontWeight = FontWeight.Medium) =
        copy(fontFamily = Pretendard, fontWeight = weight, letterSpacing = 0.1.sp, lineHeight = fontSize * 1.5f)
    Typography(
        displayLarge = displayLarge.soft(), displayMedium = displayMedium.soft(), displaySmall = displaySmall.soft(),
        headlineLarge = headlineLarge.soft(FontWeight.SemiBold), headlineMedium = headlineMedium.soft(FontWeight.SemiBold),
        headlineSmall = headlineSmall.soft(FontWeight.SemiBold),
        titleLarge = titleLarge.soft(FontWeight.SemiBold), titleMedium = titleMedium.soft(FontWeight.SemiBold),
        titleSmall = titleSmall.soft(FontWeight.SemiBold),
        bodyLarge = bodyLarge.soft(), bodyMedium = bodyMedium.soft(), bodySmall = bodySmall.soft(),
        labelLarge = labelLarge.soft(FontWeight.SemiBold), labelMedium = labelMedium.soft(), labelSmall = labelSmall.soft(),
    )
}
