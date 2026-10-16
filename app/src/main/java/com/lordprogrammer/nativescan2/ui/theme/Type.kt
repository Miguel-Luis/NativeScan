@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.lordprogrammer.nativescan2.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lordprogrammer.nativescan2.R

// Plus Jakarta Sans (fuente variable, eje wght) según docs/DESIGN.md.
private fun jakarta(weight: FontWeight) = Font(
    R.font.plus_jakarta_sans,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val PlusJakartaSans = FontFamily(
    jakarta(FontWeight.Normal),
    jakarta(FontWeight.Medium),
    jakarta(FontWeight.SemiBold),
    jakarta(FontWeight.Bold),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = PlusJakartaSans,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.em,
)

/** Escala del diseño: display, headline-lg, title-md, body-*, label-*. */
val NativeScanTypography = Typography(
    displayLarge = style(32, 40, FontWeight.Bold, -0.02f),
    headlineLarge = style(26, 34, FontWeight.Bold, -0.015f),
    headlineMedium = style(22, 28, FontWeight.Bold, -0.01f),
    titleLarge = style(22, 28, FontWeight.Bold, -0.01f),
    titleMedium = style(18, 24, FontWeight.SemiBold, -0.01f),
    titleSmall = style(14, 20, FontWeight.SemiBold, 0.01f),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(12, 16, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.SemiBold, 0.01f),
    labelMedium = style(12, 16, FontWeight.SemiBold, 0.02f),
    labelSmall = style(11, 14, FontWeight.Bold, 0.04f),
)
