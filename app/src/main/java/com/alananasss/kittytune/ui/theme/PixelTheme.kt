package com.alananasss.kittytune.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.alananasss.kittytune.R

/**
 * Only typography is fixed here — colors and shapes stay whatever the app already renders
 * (dynamic color / seed / pure black, and each component's own round/pill/square shape) so the
 * pixel theme layers the font + reskinned icons on top instead of replacing the whole look.
 */
val PixelFontFamily: FontFamily = FontFamily(Font(R.font.lores9ot, FontWeight.Normal))

/** Same type scale as the app default, only the font family changes — no other app reads pixel fonts by size. */
val PixelTypography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = PixelFontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = PixelFontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = PixelFontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = PixelFontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = PixelFontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = PixelFontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = PixelFontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = PixelFontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = PixelFontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = PixelFontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = PixelFontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = PixelFontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = PixelFontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = PixelFontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = PixelFontFamily),
    )
}
