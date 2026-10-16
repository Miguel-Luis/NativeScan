package com.lordprogrammer.nativescan2.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val NativeScanShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Tarjetas/contenedores de documento (rounded-3xl). */
val CardShape = RoundedCornerShape(24.dp)

/** Panel inferior de controles del editor. */
val DockShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
